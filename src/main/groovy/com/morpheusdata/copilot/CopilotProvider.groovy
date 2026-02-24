/*
 * Copyright 2026 Morpheus Data, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.morpheusdata.copilot

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.core.providers.LanguageModelProvider
import com.morpheusdata.model.AccountIntegration
import com.morpheusdata.model.Icon
import com.morpheusdata.model.OptionType
import com.morpheusdata.model.languageModel.*
import com.morpheusdata.response.LanguageModelStreamingResponseHandler
import com.morpheusdata.response.ServiceResponse
import groovy.util.logging.Slf4j

/**
 * LanguageModelProvider implementation for GitHub Copilot.
 * GitHub Copilot exposes an OpenAI-compatible API that provides access
 * to multiple LLM providers (OpenAI, Anthropic, Google) through a single endpoint.
 * Supports chat completion, streaming chat, and embeddings.
 *
 * @author Morpheus
 */
@Slf4j
class CopilotProvider implements LanguageModelProvider {

	Plugin plugin
	MorpheusContext morpheusContext
	CopilotApiService apiService

	static final String PROVIDER_CODE = 'github-copilot'
	static final String PROVIDER_NAME = 'GitHub Copilot'
	static final String DEFAULT_API_URL = 'https://api.githubcopilot.com'
	static final String DEFAULT_CHAT_MODEL = 'gpt-4o'
	static final String DEFAULT_EMBEDDING_MODEL = 'text-embedding-3-small'

	CopilotProvider(Plugin plugin, MorpheusContext morpheusContext) {
		this.plugin = plugin
		this.morpheusContext = morpheusContext
		this.apiService = new CopilotApiService()
	}

	@Override
	String getCode() {
		return PROVIDER_CODE
	}

	@Override
	String getName() {
		return PROVIDER_NAME
	}

	@Override
	MorpheusContext getMorpheus() {
		return this.morpheusContext
	}

	@Override
	Plugin getPlugin() {
		return this.plugin
	}

	@Override
	Icon getIcon() {
		return new Icon(path: 'copilot.svg', darkPath: 'copilot-white.svg')
	}

	@Override
	LanguageModelIntegrationType getLanguageModelIntegrationType() {
		LanguageModelIntegrationType type = new LanguageModelIntegrationType()
		type.code = PROVIDER_CODE
		type.name = PROVIDER_NAME
		type.providerService = PROVIDER_CODE
		type.chatSupported = true
		type.streamingChatSupported = true
		type.embeddingSupported = true
		type.enabled = true
		return type
	}

	@Override
	List<OptionType> getIntegrationOptionTypes() {
		List<OptionType> optionTypes = LanguageModelProvider.defaultOptionTypes(PROVIDER_CODE)
		// Set default Copilot API URL
		optionTypes.find { it.fieldName == 'serviceUrl' }?.setDefaultValue(DEFAULT_API_URL)
		// Copilot uses a GitHub token – only API key field is relevant
		OptionType credentials = new OptionType();
		credentials.setCode(PROVIDER_CODE + ".credential");
		credentials.setName("Credentials");
		credentials.setInputType(OptionType.InputType.CREDENTIAL);
		credentials.setFieldName("type");
		credentials.setFieldLabel("Credentials");
		credentials.setFieldContext("credential");
		credentials.setRequired(true);
		credentials.setDisplayOrder(1);
		credentials.setDefaultValue("local");
		credentials.setOptionSource("credentials");
		credentials.setConfig("{\"credentialTypes\":[\"api-key\"]}");
		optionTypes.add(credentials);

		return optionTypes
	}

	@Override
	ServiceResponse verifyAccountIntegration(AccountIntegration accountIntegration, Map opts) {
		try {
			String token = resolveToken(accountIntegration)
			String baseUrl = accountIntegration.serviceUrl ?: DEFAULT_API_URL
			if (!token) {
				return ServiceResponse.error('GitHub Token is required for Copilot integration')
			}
			def result = apiService.listModels(baseUrl, token)
			if (result.success) {
				return ServiceResponse.success(accountIntegration)
			} else {
				return ServiceResponse.error(result.msg ?: 'Failed to verify GitHub Copilot connection')
			}
		} catch (Exception e) {
			log.error("Error verifying Copilot integration: ${e.message}", e)
			return ServiceResponse.error("Verification failed: ${e.message}")
		}
	}

	@Override
	void refresh(AccountIntegration accountIntegration) {
		log.debug("Refreshing GitHub Copilot integration: ${accountIntegration.name}")
		try {
			String token = resolveToken(accountIntegration)
			String baseUrl = accountIntegration.serviceUrl ?: DEFAULT_API_URL
			if (!token) {
				log.warn("Cannot refresh Copilot integration – no GitHub token configured")
				return
			}
			def result = apiService.listModels(baseUrl, token)
			if (result.success && result.data) {
				syncModels(accountIntegration, result.data)
			} else {
				log.warn("Failed to refresh models from Copilot: ${result.msg}")
			}
		} catch (Exception e) {
			log.error("Error refreshing Copilot integration: ${e.message}", e)
		}
	}

	@Override
	ServiceResponse<LanguageModelCapabilities> getCapabilities(AccountIntegration accountIntegration, Map opts) {
		try {
			LanguageModelCapabilities capabilities = new LanguageModelCapabilities()
			capabilities.chatSupported = true
			capabilities.streamingChatSupported = true
			capabilities.embeddingSupported = true

			List<LanguageModel> models = loadPersistedModels(accountIntegration)
			if (!models) {
				String token = resolveToken(accountIntegration)
				String baseUrl = accountIntegration.serviceUrl ?: DEFAULT_API_URL
				def modelsResult = apiService.listModels(baseUrl, token)
				if (modelsResult.success && modelsResult.data) {
					syncModels(accountIntegration, modelsResult.data)
					models = loadPersistedModels(accountIntegration)
				}
			}
			capabilities.models = models ?: []

			return ServiceResponse.success(capabilities)
		} catch (Exception e) {
			log.error("Error fetching Copilot capabilities: ${e.message}", e)
			return ServiceResponse.error("Failed to fetch capabilities: ${e.message}")
		}
	}

	@Override
	ServiceResponse<LanguageModelChatResponse> chatCompletion(AccountIntegration accountIntegration, LanguageModelChatRequest request, Map opts) {
		try {
			String token = resolveToken(accountIntegration)
			String baseUrl = accountIntegration.serviceUrl ?: DEFAULT_API_URL
			String model = request.model ?: DEFAULT_CHAT_MODEL

			def apiMessages = request.messages?.collect { msg ->
				[role: msg.role ?: 'user', content: msg.content]
			} ?: []

			Map requestBody = [
				model   : model,
				messages: apiMessages,
				stream  : false
			]
			if (request.temperature != null) {
				requestBody.temperature = request.temperature
			}
			if (request.topP != null) {
				requestBody.top_p = request.topP
			}
			if (request.maxOutputTokens != null) {
				requestBody.max_tokens = request.maxOutputTokens
			}
			if (request.stopSequences) {
				requestBody.stop = request.stopSequences
			}

			def result = apiService.chatCompletion(baseUrl, token, requestBody)
			if (result.success && result.data) {
				LanguageModelChatResponse response = parseChatResponse(result.data)
				return ServiceResponse.success(response)
			} else {
				return ServiceResponse.error(result.msg ?: 'Chat completion failed')
			}
		} catch (Exception e) {
			log.error("Error during Copilot chat completion: ${e.message}", e)
			return ServiceResponse.error("Chat completion failed: ${e.message}")
		}
	}

	@Override
	void streamChatCompletion(AccountIntegration accountIntegration, LanguageModelChatRequest request, LanguageModelStreamingResponseHandler handler, Map opts) {
		try {
			String token = resolveToken(accountIntegration)
			String baseUrl = accountIntegration.serviceUrl ?: DEFAULT_API_URL
			String model = request.model ?: DEFAULT_CHAT_MODEL

			def apiMessages = request.messages?.collect { msg ->
				[role: msg.role ?: 'user', content: msg.content]
			} ?: []

			Map requestBody = [
				model   : model,
				messages: apiMessages,
				stream  : true
			]
			if (request.temperature != null) {
				requestBody.temperature = request.temperature
			}
			if (request.topP != null) {
				requestBody.top_p = request.topP
			}
			if (request.maxOutputTokens != null) {
				requestBody.max_tokens = request.maxOutputTokens
			}

			apiService.streamChatCompletion(baseUrl, token, requestBody, handler)
		} catch (Exception e) {
			log.error("Error during Copilot streaming chat: ${e.message}", e)
			handler?.onError(e)
		}
	}

	@Override
	ServiceResponse<LanguageModelEmbeddingResponse> generateEmbeddings(AccountIntegration accountIntegration, LanguageModelEmbeddingRequest request, Map opts) {
		try {
			String token = resolveToken(accountIntegration)
			String baseUrl = accountIntegration.serviceUrl ?: DEFAULT_API_URL
			String model = request.model ?: DEFAULT_EMBEDDING_MODEL

			Map requestBody = [
				model: model,
				input: request.input
			]

			def result = apiService.createEmbeddings(baseUrl, token, requestBody)
			if (result.success && result.data) {
				LanguageModelEmbeddingResponse response = parseEmbeddingResponse(result.data, model)
				return ServiceResponse.success(response)
			} else {
				return ServiceResponse.error(result.msg ?: 'Embedding generation failed')
			}
		} catch (Exception e) {
			log.error("Error generating Copilot embeddings: ${e.message}", e)
			return ServiceResponse.error("Embedding generation failed: ${e.message}")
		}
	}

	// --- Helper methods ---

	/**
	 * Sync persisted models with the latest API response.
	 * New models are created, removed models are disabled, reappearing models are re-enabled.
	 */
	protected void syncModels(AccountIntegration accountIntegration, Map apiResponse) {
		List<LanguageModel> freshModels = buildModelsFromApiResponse(apiResponse)
		Set<String> freshCodes = freshModels.collect { it.code } as Set

		List<LanguageModel> existingModels = loadPersistedModels(accountIntegration)
		Map<String, LanguageModel> existingByCode = existingModels.collectEntries { [(it.code): it] }

		def modelService = morpheusContext.async.getLanguageModel()
		List<LanguageModel> toSave = []
		List<LanguageModel> toCreate = []

		// Disable models that no longer appear in the API
		existingByCode.each { code, model ->
			if (!freshCodes.contains(code) && model.enabled) {
				log.info("Disabling removed Copilot model: ${code}")
				model.enabled = false
				toSave << model
			}
		}

		// Add new models, re-enable ones that reappear
		freshModels.each { freshModel ->
			LanguageModel existing = existingByCode[freshModel.code]
			if (existing) {
				boolean changed = false
				if (!existing.enabled) {
					log.info("Re-enabling Copilot model: ${freshModel.code}")
					existing.enabled = true
					changed = true
				}
				if (existing.contextWindow != freshModel.contextWindow) {
					existing.contextWindow = freshModel.contextWindow
					changed = true
				}
				if (existing.name != freshModel.name) {
					existing.name = freshModel.name
					changed = true
				}
				if (existing.modelType != freshModel.modelType) {
					existing.modelType = freshModel.modelType
					changed = true
				}
				if (changed) {
					toSave << existing
				}
			} else {
				log.info("Adding new Copilot model: ${freshModel.code}")
				toCreate << freshModel
			}
		}

		if (toCreate) {
			modelService.bulkCreate(toCreate).blockingGet()
		}
		if (toSave) {
			modelService.bulkSave(toSave).blockingGet()
		}
	}

	/**
	 * Load persisted models for this integration from the DataService.
	 */
	protected List<LanguageModel> loadPersistedModels(AccountIntegration accountIntegration) {
		try {
			def modelService = morpheusContext.async.getLanguageModel()
			return modelService.list(
				new DataQuery().withFilter('providerCode', PROVIDER_CODE)
			).toList().blockingGet() ?: []
		} catch (Exception e) {
			log.debug("Could not load persisted models: ${e.message}")
			return []
		}
	}

	/**
	 * Resolve the GitHub token from the integration credentials.
	 */
	protected String resolveToken(AccountIntegration accountIntegration) {
		return accountIntegration.credentialData?.password ?: accountIntegration.serviceToken ?: accountIntegration.servicePassword
	}

	/**
	 * Build LanguageModel list from the Copilot /models API response.
	 * Copilot returns models from multiple providers (OpenAI, Anthropic, Google).
	 */
	protected List<LanguageModel> buildModelsFromApiResponse(Map apiResponse) {
		List<LanguageModel> models = []
		def modelData = apiResponse?.data
		if (modelData instanceof List) {
			modelData.each { entry ->
				String modelId = entry.id?.toString()
				if (modelId && isChatOrEmbeddingModel(modelId)) {
					LanguageModel model = new LanguageModel()
					model.code = modelId
					model.externalId = modelId
					model.name = entry.name?.toString() ?: formatModelName(modelId)
					model.providerCode = PROVIDER_CODE
					model.modelType = categorizeModel(modelId)
					model.contextWindow = estimateContextWindow(modelId)
					model.enabled = true
					models.add(model)
				}
			}
		}
		models.sort { a, b ->
			int typeCompare = (a.modelType ?: '').compareTo(b.modelType ?: '')
			typeCompare != 0 ? typeCompare : (a.name ?: '').compareTo(b.name ?: '')
		}
		return models
	}

	/**
	 * Filter to relevant chat and embedding models from the Copilot catalog.
	 */
	protected boolean isChatOrEmbeddingModel(String modelId) {
		def prefixes = [
			// OpenAI
			'gpt-4', 'gpt-3.5', 'gpt-5', 'o1', 'o3', 'o4', 'chatgpt-4o',
			// Anthropic
			'claude-',
			// Google
			'gemini-',
			// Embeddings
			'text-embedding'
		]
		return prefixes.any { modelId.startsWith(it) }
	}

	/**
	 * Format a model ID into a human-readable display name.
	 */
	protected String formatModelName(String modelId) {
		return modelId.split('-').collect { it.capitalize() }.join(' ')
	}

	/**
	 * Categorize a model into chat or embedding type.
	 */
	protected String categorizeModel(String modelId) {
		if (modelId.startsWith('text-embedding')) {
			return 'embedding'
		}
		return 'chat'
	}

	/**
	 * Estimate context window size based on known model patterns.
	 */
	protected Long estimateContextWindow(String modelId) {
		// OpenAI models
		if (modelId.contains('gpt-4o') || modelId.contains('o1') || modelId.contains('o3') || modelId.contains('o4')) {
			return 128000L
		} else if (modelId.contains('gpt-4-turbo')) {
			return 128000L
		} else if (modelId.startsWith('gpt-4')) {
			return 8192L
		} else if (modelId.startsWith('gpt-3.5')) {
			return 16385L
		} else if (modelId.startsWith('gpt-5')) {
			return 128000L
		}
		// Anthropic Claude
		if (modelId.contains('claude-opus') || modelId.contains('claude-sonnet') || modelId.contains('claude-haiku')) {
			return 200000L
		}
		// Google Gemini
		if (modelId.startsWith('gemini-')) {
			return 128000L
		}
		// Embeddings
		if (modelId.startsWith('text-embedding-3')) {
			return 8191L
		}
		return 4096L
	}

	/**
	 * Parse OpenAI-compatible chat completion response into LanguageModelChatResponse.
	 */
	protected LanguageModelChatResponse parseChatResponse(Map data) {
		LanguageModelChatResponse response = new LanguageModelChatResponse()
		response.id = data.id?.toString()
		response.model = data.model?.toString()

		def choices = data.choices
		if (choices instanceof List && choices.size() > 0) {
			def choice = choices[0]
			response.finishReason = choice.finish_reason?.toString()
			def messageData = choice.message
			if (messageData) {
				LanguageModelChatMessage msg = new LanguageModelChatMessage()
				msg.role = messageData.role?.toString() ?: 'assistant'
				msg.content = messageData.content?.toString()
				response.message = msg
			}
		}

		def usage = data.usage
		if (usage) {
			LanguageModelTokenUsage tokenUsage = new LanguageModelTokenUsage()
			tokenUsage.inputTokens = usage.prompt_tokens as Integer
			tokenUsage.outputTokens = usage.completion_tokens as Integer
			tokenUsage.totalTokens = usage.total_tokens as Integer
			response.tokenUsage = tokenUsage
		}

		return response
	}

	/**
	 * Parse OpenAI-compatible embeddings response into LanguageModelEmbeddingResponse.
	 */
	protected LanguageModelEmbeddingResponse parseEmbeddingResponse(Map data, String model) {
		LanguageModelEmbeddingResponse response = new LanguageModelEmbeddingResponse()
		response.model = data.model?.toString() ?: model

		def embeddingData = data.data
		if (embeddingData instanceof List) {
			List<List<Double>> embeddings = []
			embeddingData.each { entry ->
				if (entry.embedding instanceof List) {
					embeddings.add(entry.embedding.collect { it as Double })
				}
			}
			response.embeddings = embeddings
		}

		def usage = data.usage
		if (usage) {
			LanguageModelTokenUsage tokenUsage = new LanguageModelTokenUsage()
			tokenUsage.inputTokens = usage.prompt_tokens as Integer
			tokenUsage.totalTokens = usage.total_tokens as Integer
			response.tokenUsage = tokenUsage
		}

		return response
	}
}
