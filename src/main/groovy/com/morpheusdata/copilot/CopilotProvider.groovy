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
import com.morpheusdata.copilot.sync.LlmModelsSync
import com.morpheusdata.copilot.sync.LlmUsageSync
import com.morpheusdata.core.providers.LlmProvider
import com.morpheusdata.model.AccountIntegration
import com.morpheusdata.model.Icon
import com.morpheusdata.model.OptionType
import com.morpheusdata.model.llm.*
import com.morpheusdata.response.LlmStreamingResponseHandler
import com.morpheusdata.response.ServiceResponse
import groovy.util.logging.Slf4j

/**
 * LlmProvider implementation for GitHub Copilot.
 * GitHub Copilot exposes an OpenAI-compatible API that provides access
 * to multiple LLM providers (OpenAI, Anthropic, Google) through a single endpoint.
 * Supports response generation and streaming.
 *
 * @author Morpheus
 */
@Slf4j
class CopilotProvider implements LlmProvider {

	Plugin plugin
	MorpheusContext morpheusContext
	CopilotApiService apiService

	static final String PROVIDER_CODE = 'github-copilot'
	static final String PROVIDER_NAME = 'GitHub Copilot'
	static final String DEFAULT_API_URL = 'https://api.githubcopilot.com'
static final String DEFAULT_CHAT_MODEL = 'claude-sonnet-4.6'

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
	Boolean getChatSupported() {
		return true
	}

	@Override
	Boolean getStreamingChatSupported() {
		return true
	}

	@Override
	Boolean getEmbeddingSupported() {
		return false
	}

	@Override
	List<OptionType> getOptionTypes() {
		List<OptionType> optionTypes = []

		// Set default Copilot API URL
		OptionType serviceUrl = new OptionType(
			code: "${PROVIDER_CODE}.serviceUrl",
			name: "Service URL",
			fieldName: "serviceUrl",
			fieldLabel: "API Endpoint",
			fieldContext: "domain",
			inputType: OptionType.InputType.TEXT,
			displayOrder: 0,
			required: true,
			defaultValue: DEFAULT_API_URL
		)
		optionTypes.add(serviceUrl);

		// Copilot uses a GitHub token - only API key field is relevant
		OptionType credentials = new OptionType(
			code: "${PROVIDER_CODE}.credential",
			name: "Credentials",
			inputType: OptionType.InputType.CREDENTIAL,
			fieldName: "type",
			fieldLabel: "Credentials",
			fieldContext: "credential",
			required: true,
			displayOrder: 1,
			defaultValue: "local",
			optionSource: "credentials",
			config: '{"credentialTypes":["api-key"]}'
		)
		optionTypes.add(credentials);

		return optionTypes
	}

	@Override
	ServiceResponse validate(LlmIntegration llmIntegration, Map opts) {
		try {
			AccountIntegration accountIntegration = llmIntegration?.accountIntegration
			if(!accountIntegration) {
				return ServiceResponse.error('Account integration is required for Copilot integration')
			}
			String token = resolveToken(accountIntegration)
			String baseUrl = accountIntegration.serviceUrl ?: DEFAULT_API_URL
			if (!token) {
				return ServiceResponse.error('GitHub Token is required for Copilot integration')
			}
			def result = apiService.listModels(baseUrl, token)
			if (result.success) {
				return ServiceResponse.success(llmIntegration)
			} else {
				return ServiceResponse.error(result.msg ?: 'Failed to verify GitHub Copilot connection')
			}
		} catch (Exception e) {
			log.error("Error verifying Copilot integration: ${e.message}", e)
			return ServiceResponse.error("Verification failed: ${e.message}")
		}
	}

	@Override
	void refresh(LlmIntegration llmIntegration) {
		AccountIntegration accountIntegration = llmIntegration?.accountIntegration
		if(!accountIntegration) {
			log.warn("Cannot refresh Copilot integration – account integration is missing")
			return
		}
		log.debug("Refreshing GitHub Copilot integration: ${accountIntegration.name}")
		try {
			String token = resolveToken(accountIntegration)
			String baseUrl = accountIntegration.serviceUrl ?: DEFAULT_API_URL
			if (!token) {
				log.warn("Cannot refresh Copilot integration – no GitHub token configured")
				return
			}
			new LlmModelsSync(llmIntegration).execute(morpheusContext, apiService, PROVIDER_CODE, baseUrl, token) { Map apiResponse ->
				buildModelsFromApiResponse(llmIntegration, apiResponse)
			}
			new LlmUsageSync(llmIntegration, morpheusContext).execute(apiService, baseUrl, token, DEFAULT_CHAT_MODEL)
		} catch (Exception e) {
			log.error("Error refreshing Copilot integration: ${e.message}", e)
		}
	}

	@Override
	ServiceResponse<LlmChatResponse> generateResponse(LlmIntegration llmIntegration, LlmChatRequest request, Map opts) {
		try {
			AccountIntegration accountIntegration = llmIntegration?.accountIntegration
			if(!accountIntegration) {
				return ServiceResponse.error('Account integration is required for Copilot integration')
			}
			String token = resolveToken(accountIntegration)
			String baseUrl = accountIntegration.serviceUrl ?: DEFAULT_API_URL
			String model = request.model ?: DEFAULT_CHAT_MODEL
			LlmModel persistedModel = loadPersistedModel(llmIntegration, model)
			String interactionEndpoint = resolveInteractionEndpoint(model, persistedModel)
			Map requestBody = buildInteractionRequestBody(request, model, interactionEndpoint, false)
			def result = apiService.interact(baseUrl, token, interactionEndpoint, requestBody)
			if (result.success && result.data) {
				LlmChatResponse response = isResponsesEndpoint(interactionEndpoint) ? parseResponsesChatResponse(result.data) : parseChatResponse(result.data)
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
	void streamResponse(LlmIntegration llmIntegration, LlmChatRequest request, LlmStreamingResponseHandler handler, Map opts) {
		try {
			AccountIntegration accountIntegration = llmIntegration?.accountIntegration
			if(!accountIntegration) {
				handler?.onError(new IllegalArgumentException('Account integration is required for Copilot integration'))
				return
			}
			String token = resolveToken(accountIntegration)
			String baseUrl = accountIntegration.serviceUrl ?: DEFAULT_API_URL
			String model = request.model ?: DEFAULT_CHAT_MODEL
			LlmModel persistedModel = loadPersistedModel(llmIntegration, model)
			String streamingEndpoint = resolveStreamingInteractionEndpoint(model, persistedModel)

			if(!streamingEndpoint) {
				ServiceResponse<LlmChatResponse> response = generateResponse(llmIntegration, request, opts)
				if(response?.success && response?.data) {
					String content = response.data.message?.content
					if(content) {
						handler?.onPartialResponse(content)
					}
					handler?.onCompleteResponse(response.data)
				} else {
					handler?.onError(new RuntimeException(response?.errors?.error ?: 'Chat completion failed'))
				}
				return
			}

			Map requestBody = buildInteractionRequestBody(request, model, streamingEndpoint, true)
			apiService.streamInteract(baseUrl, token, streamingEndpoint, requestBody, handler)
		} catch (Exception e) {
			log.error("Error during Copilot streaming chat: ${e.message}", e)
			handler?.onError(e)
		}
	}

	// --- Helper methods ---

	/**
	 * Resolve the GitHub token from the integration credentials.
	 */
	protected String resolveToken(AccountIntegration accountIntegration) {
		return accountIntegration.credentialData?.password ?: accountIntegration.serviceToken ?: accountIntegration.servicePassword
	}

	/**
	 * Build LlmModel list from the Copilot /models API response.
	 * Copilot returns models from multiple providers (OpenAI, Anthropic, Google).
	 */
	protected List<LlmModel> buildModelsFromApiResponse(LlmIntegration llmIntegration, Map apiResponse) {
		List<LlmModel> models = []
		def modelData = apiResponse?.data
		if (modelData instanceof List) {
			modelData.each { entry ->
				String modelId = entry.id?.toString()
				if (modelId && isChatOrEmbeddingModel(modelId)) {
					String modelType = categorizeModel(modelId)
					Map<String, Object> capabilities = extractCapabilities(entry)
					List<String> supportedEndpoints = normalizeSupportedEndpoints(entry?.supported_endpoints, modelId, modelType)
					String interactionEndpoint = selectInteractionEndpoint(supportedEndpoints, modelId, modelType)
					String streamingInteractionEndpoint = selectStreamingInteractionEndpoint(capabilities, supportedEndpoints, interactionEndpoint, modelType)
					Map<String, Object> metadata = LlmModelMetadata.buildMetadata(capabilities, supportedEndpoints, interactionEndpoint, streamingInteractionEndpoint)

					LlmModel model = new LlmModel()
					model.code = modelId
					model.externalId = modelId
					model.name = entry.name?.toString() ?: formatModelName(modelId)
					model.providerCode = PROVIDER_CODE
					model.modelType = modelType
					model.contextWindow = estimateContextWindow(modelId)
					Long maxOutputTokens = extractMaxOutputTokens(capabilities)
					if(maxOutputTokens != null) {
						model.maxOutputTokens = maxOutputTokens
					}
					if(metadata) {
						model.metadata = metadata
						model.configMap = metadata
					}
					model.llmIntegration = llmIntegration
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

	protected LlmModel loadPersistedModel(LlmIntegration llmIntegration, String modelCode) {
		if(!modelCode || !llmIntegration?.id) {
			return null
		}
		try {
			def modelService = morpheusContext.llm.model
			DataQuery query = new DataQuery()
				.withFilter('providerCode', PROVIDER_CODE)
				.withFilter('code', modelCode)
				.withFilter('llmIntegration.id', llmIntegration.id)
			return modelService.list(query).toList().blockingGet()?.find { it.code == modelCode }
		} catch (Exception ex) {
			log.debug("Could not load persisted model ${modelCode}: ${ex.message}")
			return null
		}
	}

	protected String resolveInteractionEndpoint(String modelId, LlmModel persistedModel) {
		String endpoint = LlmModelMetadata.getInteractionEndpoint(persistedModel)
		if(endpoint) {
			return endpoint
		}
		List<String> supportedEndpoints = LlmModelMetadata.getSupportedEndpoints(persistedModel)
		String modelType = persistedModel?.modelType ?: categorizeModel(modelId)
		if(!supportedEndpoints) {
			supportedEndpoints = defaultSupportedEndpoints(modelId, modelType)
		}
		return selectInteractionEndpoint(supportedEndpoints, modelId, modelType)
	}

	protected String resolveStreamingInteractionEndpoint(String modelId, LlmModel persistedModel) {
		String endpoint = LlmModelMetadata.getStreamingInteractionEndpoint(persistedModel)
		if(endpoint) {
			return endpoint
		}
		String modelType = persistedModel?.modelType ?: categorizeModel(modelId)
		List<String> supportedEndpoints = LlmModelMetadata.getSupportedEndpoints(persistedModel)
		if(!supportedEndpoints) {
			supportedEndpoints = defaultSupportedEndpoints(modelId, modelType)
		}
		String interactionEndpoint = resolveInteractionEndpoint(modelId, persistedModel)
		Boolean streamingSupported = LlmModelMetadata.getStreamingSupported(persistedModel)
		return selectStreamingInteractionEndpoint(LlmModelMetadata.getCapabilities(persistedModel), supportedEndpoints, interactionEndpoint, modelType, streamingSupported)
	}

	protected Map buildInteractionRequestBody(LlmChatRequest request, String model, String endpointPath, Boolean stream) {
		def apiMessages = request.messages?.collect { msg ->
			def m = [role: msg.role ?: 'user', content: msg.content]
			// Pass through tool_calls and tool_call_id for multi-turn tool conversations
			if (msg.metadata?.tool_calls) {
				// Ensure arguments are JSON strings (OpenAI API requirement)
				m.tool_calls = msg.metadata.tool_calls.collect { tc ->
					def copy = new LinkedHashMap(tc)
					if (copy.function?.arguments != null && !(copy.function.arguments instanceof String)) {
						copy.function = new LinkedHashMap(copy.function)
						copy.function.arguments = new groovy.json.JsonBuilder(copy.function.arguments).toString()
					}
					return copy
				}
			}
			if (msg.metadata?.tool_call_id) {
				m.tool_call_id = msg.metadata.tool_call_id
			}
			return m
		} ?: []
		Map requestBody
		if(isResponsesEndpoint(endpointPath)) {
			requestBody = [
				model: model,
				input: apiMessages
			]
			if (request.temperature != null) {
				requestBody.temperature = request.temperature
			}
			if (request.topP != null) {
				requestBody.top_p = request.topP
			}
			if (request.maxOutputTokens != null) {
				requestBody.max_output_tokens = request.maxOutputTokens
			}
			if (request.stopSequences) {
				requestBody.stop = request.stopSequences
			}
		} else {
			requestBody = [
				model   : model,
				messages: apiMessages
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
			// Pass through tool definitions from request options for function calling
			if (request.options?.tools) {
				requestBody.tools = request.options.tools
			}
			if (request.options?.tool_choice) {
				requestBody.tool_choice = request.options.tool_choice
			}
		}
		if(stream != null) {
			requestBody.stream = stream
		}
		return requestBody
	}

	protected Map<String, Object> extractCapabilities(Map entry) {
		def capabilities = entry?.capabilities
		if(capabilities instanceof Map) {
			return new LinkedHashMap<>(capabilities as Map<String, Object>)
		}
		return [:]
	}

	protected Long extractMaxOutputTokens(Map<String, Object> capabilities) {
		def limits = capabilities?.limits
		if(!(limits instanceof Map)) {
			return null
		}
		def value = limits.max_output_tokens ?: limits.max_non_streaming_output_tokens
		if(value instanceof Number) {
			return (value as Number).longValue()
		}
		if(value != null) {
			try {
				return value.toString().toLong()
			} catch(Exception ignored) {
				return null
			}
		}
		return null
	}

	protected List<String> normalizeSupportedEndpoints(def supportedEndpoints, String modelId, String modelType) {
		List<String> endpoints = []
		if(supportedEndpoints instanceof List) {
			supportedEndpoints.each { endpoint ->
				if(endpoint) {
					endpoints << endpoint.toString()
				}
			}
		}
		if(!endpoints) {
			endpoints = defaultSupportedEndpoints(modelId, modelType)
		}
		return endpoints.unique()
	}

	protected List<String> defaultSupportedEndpoints(String modelId, String modelType) {
		if(modelType == 'embedding') {
			return [CopilotApiService.EMBEDDINGS_PATH]
		}
		if(modelId?.toLowerCase()?.contains('codex')) {
			return [CopilotApiService.RESPONSES_PATH]
		}
		return [CopilotApiService.CHAT_COMPLETIONS_PATH]
	}

	protected String selectInteractionEndpoint(List<String> supportedEndpoints, String modelId, String modelType) {
		if(modelType == 'embedding') {
			return supportedEndpoints.find { it?.toLowerCase()?.contains('embedding') } ?: CopilotApiService.EMBEDDINGS_PATH
		}
		String responsesPath = supportedEndpoints.find { isResponsesEndpoint(it) }
		String chatPath = supportedEndpoints.find { isChatCompletionsEndpoint(it) }
		if(modelId?.toLowerCase()?.contains('codex') && responsesPath) {
			return responsesPath
		}
		if(chatPath) {
			return chatPath
		}
		if(responsesPath) {
			return responsesPath
		}
		return supportedEndpoints ? supportedEndpoints[0] : CopilotApiService.CHAT_COMPLETIONS_PATH
	}

	protected String selectStreamingInteractionEndpoint(Map<String, Object> capabilities, List<String> supportedEndpoints, String interactionEndpoint, String modelType, Boolean streamingSupported = null) {
		if(modelType == 'embedding') {
			return null
		}
		if(streamingSupported == null) {
			streamingSupported = capabilities?.supports instanceof Map ? capabilities.supports.streaming as Boolean : null
		}
		if(streamingSupported == Boolean.FALSE) {
			return null
		}
		if(interactionEndpoint && isChatCompletionsEndpoint(interactionEndpoint)) {
			return interactionEndpoint
		}
		String streamingCandidate = supportedEndpoints?.find { isChatCompletionsEndpoint(it) }
		if(streamingCandidate) {
			return streamingCandidate
		}
		return null
	}

	protected boolean isResponsesEndpoint(String endpointPath) {
		return endpointPath?.toLowerCase()?.contains('/responses')
	}

	protected boolean isChatCompletionsEndpoint(String endpointPath) {
		return endpointPath?.toLowerCase()?.contains('/chat/completions')
	}

	protected LlmChatResponse parseResponsesChatResponse(Map data) {
		LlmChatResponse response = new LlmChatResponse()
		response.id = data.id?.toString()
		response.model = data.model?.toString()
		String status = data.status?.toString()
		if(status) {
			response.finishReason = status == 'completed' ? 'stop' : status
		}

		String responseText = extractResponsesText(data)
		if(responseText != null) {
			LlmChatMessage msg = new LlmChatMessage()
			msg.role = 'assistant'
			msg.content = responseText
			response.message = msg
		}

		def usage = data.usage
		if (usage) {
			LlmTokenUsage tokenUsage = new LlmTokenUsage()
			tokenUsage.inputTokens = usage.input_tokens as Integer
			tokenUsage.outputTokens = usage.output_tokens as Integer
			tokenUsage.totalTokens = usage.total_tokens as Integer
			response.tokenUsage = tokenUsage
		}

		return response
	}

	protected String extractResponsesText(Map data) {
		if(data.output_text != null) {
			return data.output_text.toString()
		}
		StringBuilder textBuilder = new StringBuilder()
		def output = data.output
		if(output instanceof List) {
			output.each { outputItem ->
				def contentItems = outputItem?.content
				if(contentItems instanceof List) {
					contentItems.each { contentItem ->
						String textValue = contentItem?.text?.toString() ?: contentItem?.output_text?.toString()
						if(textValue) {
							textBuilder.append(textValue)
						}
					}
				}
			}
		}
		return textBuilder.length() > 0 ? textBuilder.toString() : null
	}

	/**
	 * Parse OpenAI-compatible chat completion response into LlmChatResponse.
	 * Extracts tool_calls from the response message when the model requests
	 * function/tool invocations, storing them in response metadata so the
	 * caller can execute them.
	 */
	protected LlmChatResponse parseChatResponse(Map data) {
		LlmChatResponse response = new LlmChatResponse()
		response.id = data.id?.toString()
		response.model = data.model?.toString()

		def choices = data.choices
		if (choices instanceof List && choices.size() > 0) {
			def choice = choices[0]
			response.finishReason = choice.finish_reason?.toString()
			def messageData = choice.message
			if (messageData) {
				LlmChatMessage msg = new LlmChatMessage()
				msg.role = messageData.role?.toString() ?: 'assistant'
				msg.content = messageData.content?.toString()
				response.message = msg

				// Extract tool_calls from the OpenAI-format response
				def toolCalls = messageData.tool_calls
				if (toolCalls instanceof List && toolCalls.size() > 0) {
					List<Map> normalizedToolCalls = toolCalls.collect { tc ->
						Map toolCall = [
							id: tc.id?.toString() ?: UUID.randomUUID().toString(),
							type: tc.type?.toString() ?: 'function'
						]
						if (tc.function instanceof Map) {
							toolCall.function = [
								name: tc.function.name?.toString(),
								arguments: tc.function.arguments?.toString() ?: '{}'
							]
						}
						return toolCall
					}
					response.metadata.put('tool_calls', normalizedToolCalls)
				}
			}
		}

		def usage = data.usage
		if (usage) {
			LlmTokenUsage tokenUsage = new LlmTokenUsage()
			tokenUsage.inputTokens = usage.prompt_tokens as Integer
			tokenUsage.outputTokens = usage.completion_tokens as Integer
			tokenUsage.totalTokens = usage.total_tokens as Integer
			response.tokenUsage = tokenUsage
		}

		return response
	}

}
