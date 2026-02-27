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
import com.morpheusdata.core.integration.MorpheusLlmModelService
import com.morpheusdata.core.integration.MorpheusLlmService
import com.morpheusdata.model.AccountIntegration
import com.morpheusdata.model.llm.*
import com.morpheusdata.response.LlmStreamingResponseHandler
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.Single
import spock.lang.Specification

class CopilotProviderSpec extends Specification {

	CopilotProvider provider
	Plugin plugin
	MorpheusContext morpheusContext
	MorpheusLlmService llmService
	MorpheusLlmModelService modelService
	CopilotApiService apiService

	def setup() {
		plugin = Mock(Plugin)
		morpheusContext = Mock(MorpheusContext)
		llmService = Mock(MorpheusLlmService)
		modelService = Mock(MorpheusLlmModelService)

		morpheusContext.getLlm() >> llmService
		llmService.getModel() >> modelService
		modelService.list(_) >> Observable.empty()
		modelService.bulkCreate(_) >> Single.just(true)
		modelService.bulkSave(_) >> Single.just(true)

		provider = new CopilotProvider(plugin, morpheusContext)
		apiService = Mock(CopilotApiService)
		provider.apiService = apiService
	}

	// --- Provider metadata ---

	def "provider returns correct code"() {
		expect:
		provider.getCode() == 'github-copilot'
	}

	def "provider returns correct name"() {
		expect:
		provider.getName() == 'GitHub Copilot'
	}

	def "provider returns icon with correct paths"() {
		expect:
		provider.getIcon().path == 'copilot.svg'
		provider.getIcon().darkPath == 'copilot-white.svg'
	}

	def "provider capability fields are set correctly"() {
		expect:
		provider.chatSupported
		provider.streamingChatSupported
		!provider.embeddingSupported
	}

	def "provider returns integration option types with copilot defaults"() {
		when:
		def opts = provider.getOptionTypes()

		then:
		opts != null
		opts.size() >= 1
		def urlOpt = opts.find { it.fieldName == 'serviceUrl' }
		urlOpt.defaultValue == 'https://api.githubcopilot.com'
		def credOpt = opts.find { it.fieldContext == 'credential' }
		credOpt.name == 'Credentials'
	}

	// --- Verify ---

	def "validate succeeds when API returns models"() {
		given:
		AccountIntegration accountIntegration = new AccountIntegration(
			serviceUrl: 'https://api.githubcopilot.com',
			serviceToken: 'gh-tok-123'
		)
		LlmIntegration integration = languageModelIntegration(accountIntegration)

		when:
		def result = provider.validate(integration, [:])

		then:
		1 * apiService.listModels('https://api.githubcopilot.com', 'gh-tok-123') >> [success: true, data: [data: []]]
		result.success
	}

	def "validate fails when token is missing"() {
		given:
		AccountIntegration accountIntegration = new AccountIntegration(serviceUrl: 'https://api.githubcopilot.com')
		LlmIntegration integration = languageModelIntegration(accountIntegration)

		when:
		def result = provider.validate(integration, [:])

		then:
		!result.success
		result.errors?.error?.contains('GitHub Token is required')
	}

	// --- Models ---

	def "listModels refreshes from API when cache is empty"() {
		given:
		AccountIntegration accountIntegration = new AccountIntegration(
			serviceUrl: 'https://api.githubcopilot.com',
			serviceToken: 'gh-tok-123'
		)
		LlmIntegration integration = languageModelIntegration(accountIntegration)

		when:
		def result = provider.listModels(integration, [:])

		then:
		1 * apiService.listModels('https://api.githubcopilot.com', 'gh-tok-123') >> [success: true, data: [data: [[id: 'gpt-4o', name: 'GPT-4o']]]]
		result.success
		result.data instanceof List
	}

	// --- Chat completion ---

	def "chatCompletion succeeds with valid response"() {
		given:
		AccountIntegration accountIntegration = new AccountIntegration(
			serviceUrl: 'https://api.githubcopilot.com',
			serviceToken: 'gh-tok-123'
		)
		LlmIntegration integration = languageModelIntegration(accountIntegration)
		LlmChatRequest request = new LlmChatRequest()
		request.model = 'gpt-4o'
		LlmChatMessage msg = new LlmChatMessage()
		msg.role = 'user'
		msg.content = 'Hello'
		request.messages = [msg]

		when:
		def result = provider.generateResponse(integration, request, [:])

		then:
		1 * apiService.interact('https://api.githubcopilot.com', 'gh-tok-123', '/chat/completions', _) >> [
			success: true,
			data   : [
				id     : 'chatcmpl-abc',
				model  : 'gpt-4o',
				choices: [
					[
						message      : [role: 'assistant', content: 'Hi there!'],
						finish_reason: 'stop'
					]
				],
				usage  : [prompt_tokens: 5, completion_tokens: 10, total_tokens: 15]
			]
		]
		result.success
		result.data.message.content == 'Hi there!'
		result.data.tokenUsage.totalTokens == 15
	}

	def "chatCompletion defaults model to gpt-4o when not specified"() {
		given:
		AccountIntegration accountIntegration = new AccountIntegration(
			serviceUrl: 'https://api.githubcopilot.com',
			serviceToken: 'gh-tok-123'
		)
		LlmIntegration integration = languageModelIntegration(accountIntegration)
		LlmChatRequest request = new LlmChatRequest()
		LlmChatMessage msg = new LlmChatMessage()
		msg.role = 'user'
		msg.content = 'Test'
		request.messages = [msg]

		when:
		provider.generateResponse(integration, request, [:])

		then:
		1 * apiService.interact(_, _, '/chat/completions', { Map body -> body.model == 'gpt-4o' }) >> [success: true, data: [choices: [[message: [content: 'ok']]]]]
	}

	def "chatCompletion routes codex models to responses endpoint"() {
		given:
		AccountIntegration accountIntegration = new AccountIntegration(
			serviceUrl: 'https://api.githubcopilot.com',
			serviceToken: 'gh-tok-123'
		)
		LlmIntegration integration = languageModelIntegration(accountIntegration)
		LlmChatRequest request = new LlmChatRequest()
		request.model = 'gpt-5.1-codex'
		request.maxOutputTokens = 2048
		LlmChatMessage msg = new LlmChatMessage()
		msg.role = 'user'
		msg.content = 'Refactor this method'
		request.messages = [msg]

		when:
		def result = provider.generateResponse(integration, request, [:])

		then:
		1 * apiService.interact('https://api.githubcopilot.com', 'gh-tok-123', '/responses', { Map body ->
			body.model == 'gpt-5.1-codex' && body.input instanceof List && body.max_output_tokens == 2048 && body.stream == false
		}) >> [
			success: true,
			data   : [
				id         : 'resp_123',
				model      : 'gpt-5.1-codex',
				status     : 'completed',
				output_text: 'Done',
				usage      : [input_tokens: 12, output_tokens: 8, total_tokens: 20]
			]
		]
		result.success
		result.data.message.content == 'Done'
		result.data.tokenUsage.totalTokens == 20
	}

	def "chatCompletion handles API failure"() {
		given:
		AccountIntegration accountIntegration = new AccountIntegration(
			serviceUrl: 'https://api.githubcopilot.com',
			serviceToken: 'gh-tok-123'
		)
		LlmIntegration integration = languageModelIntegration(accountIntegration)
		LlmChatRequest request = new LlmChatRequest()
		request.model = 'gpt-4o'
		request.messages = []

		when:
		def result = provider.generateResponse(integration, request, [:])

		then:
		1 * apiService.interact(_, _, '/chat/completions', _) >> [success: false, msg: 'Rate limited']
		!result.success
		result.errors?.error?.contains('Rate limited')
	}

	// --- Streaming ---

	def "streamChatCompletion calls apiService with stream true"() {
		given:
		AccountIntegration accountIntegration = new AccountIntegration(
			serviceUrl: 'https://api.githubcopilot.com',
			serviceToken: 'gh-tok-123'
		)
		LlmIntegration integration = languageModelIntegration(accountIntegration)
		LlmChatRequest request = new LlmChatRequest()
		request.model = 'claude-sonnet-4'
		LlmChatMessage msg = new LlmChatMessage()
		msg.role = 'user'
		msg.content = 'Stream test'
		request.messages = [msg]
		LlmStreamingResponseHandler handler = Mock()

		when:
		provider.streamResponse(integration, request, handler, [:])

		then:
		1 * apiService.streamInteract('https://api.githubcopilot.com', 'gh-tok-123', '/chat/completions', { Map body ->
			body.model == 'claude-sonnet-4' && body.stream == true
		}, handler)
	}

	def "streamChatCompletion for codex models uses responses fallback"() {
		given:
		AccountIntegration accountIntegration = new AccountIntegration(
			serviceUrl: 'https://api.githubcopilot.com',
			serviceToken: 'gh-tok-123'
		)
		LlmIntegration integration = languageModelIntegration(accountIntegration)
		LlmChatRequest request = new LlmChatRequest()
		request.model = 'gpt-5.1-codex'
		LlmChatMessage msg = new LlmChatMessage()
		msg.role = 'user'
		msg.content = 'Generate code'
		request.messages = [msg]
		LlmStreamingResponseHandler handler = Mock()

		when:
		provider.streamResponse(integration, request, handler, [:])

		then:
		1 * apiService.interact('https://api.githubcopilot.com', 'gh-tok-123', '/responses', { Map body ->
			body.model == 'gpt-5.1-codex' && body.input instanceof List && body.stream == false
		}) >> [
			success: true,
			data   : [
				id         : 'resp_456',
				model      : 'gpt-5.1-codex',
				status     : 'completed',
				output_text: 'Codex output'
			]
		]
		0 * apiService.streamInteract(_, _, _, _, _)
		1 * handler.onPartialResponse('Codex output')
		1 * handler.onCompleteResponse({ LlmChatResponse response ->
			response.message?.content == 'Codex output'
		})
	}

	// --- Model building & categorization ---

	def "buildModelsFromApiResponse parses models correctly"() {
		given:
		AccountIntegration accountIntegration = new AccountIntegration(id: 7L)
		LlmIntegration integration = languageModelIntegration(accountIntegration)
		Map apiResponse = [
			data: [
				[
					id                 : 'gpt-4o',
					name               : 'GPT-4o',
					supported_endpoints: ['/chat/completions'],
					capabilities       : [supports: [streaming: true], limits: [max_output_tokens: 4096]]
				],
				[
					id                 : 'claude-sonnet-4',
					name               : 'Claude Sonnet 4',
					supported_endpoints: ['/chat/completions'],
					capabilities       : [supports: [streaming: true]]
				],
				[
					id                 : 'text-embedding-3-small',
					name               : 'Text Embedding 3 Small',
					supported_endpoints: ['/v1/embeddings'],
					capabilities       : [type: 'embedding']
				],
				[id: 'some-unknown-model', name: 'Unknown']
			]
		]

		when:
		def models = provider.buildModelsFromApiResponse(integration, apiResponse)

		then:
		models.size() == 3  // unknown model is filtered out
		models*.code.contains('gpt-4o')
		models*.code.contains('claude-sonnet-4')
		models*.code.contains('text-embedding-3-small')
		models.every { it.languageModelIntegration?.code == 'language-model-7' }
		models.find { it.code == 'gpt-4o' }?.metadata?.interactionEndpoint == '/chat/completions'
		models.find { it.code == 'gpt-4o' }?.maxOutputTokens == 4096L
		models.find { it.code == 'text-embedding-3-small' }?.metadata?.interactionEndpoint == '/v1/embeddings'
	}

	def "categorizeModel returns correct types"() {
		expect:
		provider.categorizeModel('gpt-4o') == 'chat'
		provider.categorizeModel('claude-sonnet-4') == 'chat'
		provider.categorizeModel('gemini-2.5-pro') == 'chat'
		provider.categorizeModel('text-embedding-3-small') == 'embedding'
	}

	def "estimateContextWindow returns expected values"() {
		expect:
		provider.estimateContextWindow('gpt-4o') == 128000L
		provider.estimateContextWindow('gpt-4-turbo') == 128000L
		provider.estimateContextWindow('gpt-3.5-turbo') == 16385L
		provider.estimateContextWindow('claude-sonnet-4') == 200000L
		provider.estimateContextWindow('claude-haiku-4.5') == 200000L
		provider.estimateContextWindow('gemini-2.5-pro') == 128000L
		provider.estimateContextWindow('text-embedding-3-small') == 8191L
	}

	def "isChatOrEmbeddingModel filters correctly"() {
		expect:
		provider.isChatOrEmbeddingModel('gpt-4o')
		provider.isChatOrEmbeddingModel('claude-sonnet-4')
		provider.isChatOrEmbeddingModel('gemini-2.5-pro')
		provider.isChatOrEmbeddingModel('text-embedding-3-small')
		!provider.isChatOrEmbeddingModel('some-random-model')
		!provider.isChatOrEmbeddingModel('dall-e-3')
	}

	// --- Model sync ---

	def "syncModels creates new models"() {
		given:
		AccountIntegration accountIntegration = new AccountIntegration(id: 123L, serviceToken: 'tok')
		LlmIntegration integration = languageModelIntegration(accountIntegration)
		Map apiResponse = [data: [[id: 'gpt-4o', name: 'GPT-4o']]]

		when:
		provider.syncModels(integration, apiResponse)

		then:
		1 * modelService.list(_) >> Observable.empty()
		1 * modelService.bulkCreate({ List models ->
			models.size() == 1 && models[0].code == 'gpt-4o' && models[0].languageModelIntegration?.code == 'language-model-123'
		}) >> Single.just(true)
	}

	def "syncModels disables removed models"() {
		given:
		AccountIntegration accountIntegration = new AccountIntegration(id: 123L, serviceToken: 'tok')
		LlmIntegration integration = languageModelIntegration(accountIntegration)
		Map apiResponse = [data: []]
		LlmModel existing = new LlmModel()
		existing.code = 'gpt-4o'
		existing.enabled = true
		existing.providerCode = 'github-copilot'

		when:
		provider.syncModels(integration, apiResponse)

		then:
		1 * modelService.list(_) >> Observable.just(existing)
		1 * modelService.bulkSave({ List models -> models.size() == 1 && !models[0].enabled }) >> Single.just(true)
	}

	def "syncModels re-enables reappearing models"() {
		given:
		AccountIntegration accountIntegration = new AccountIntegration(id: 123L, serviceToken: 'tok')
		LlmIntegration integration = languageModelIntegration(accountIntegration)
		Map apiResponse = [data: [[id: 'gpt-4o', name: 'GPT-4o']]]
		LlmModel existing = new LlmModel()
		existing.code = 'gpt-4o'
		existing.enabled = false
		existing.name = 'GPT-4o'
		existing.modelType = 'chat'
		existing.contextWindow = 128000L
		existing.providerCode = 'github-copilot'

		when:
		provider.syncModels(integration, apiResponse)

		then:
		1 * modelService.list(_) >> Observable.just(existing)
		1 * modelService.bulkSave({ List models -> models.size() == 1 && models[0].enabled }) >> Single.just(true)
	}

	// --- Response parsing ---

	def "parseChatResponse handles full response"() {
		given:
		Map data = [
			id     : 'chatcmpl-xyz',
			model  : 'gpt-4o',
			choices: [
				[
					message      : [role: 'assistant', content: 'Hello!'],
					finish_reason: 'stop'
				]
			],
			usage  : [prompt_tokens: 10, completion_tokens: 5, total_tokens: 15]
		]

		when:
		def response = provider.parseChatResponse(data)

		then:
		response.id == 'chatcmpl-xyz'
		response.model == 'gpt-4o'
		response.message.content == 'Hello!'
		response.message.role == 'assistant'
		response.finishReason == 'stop'
		response.tokenUsage.inputTokens == 10
		response.tokenUsage.outputTokens == 5
		response.tokenUsage.totalTokens == 15
	}

	def "parseChatResponse handles empty choices"() {
		given:
		Map data = [id: 'test', model: 'gpt-4o', choices: []]

		when:
		def response = provider.parseChatResponse(data)

		then:
		response.id == 'test'
		response.message == null
	}

	// --- Token resolution ---

	def "resolveToken prefers credentialData.password"() {
		given:
		AccountIntegration integration = new AccountIntegration()
		integration.credentialData = [password: 'cred-token']
		integration.serviceToken = 'service-token'

		expect:
		provider.resolveToken(integration) == 'cred-token'
	}

	def "resolveToken falls back to serviceToken"() {
		given:
		AccountIntegration integration = new AccountIntegration()
		integration.serviceToken = 'service-token'

		expect:
		provider.resolveToken(integration) == 'service-token'
	}

	def "resolveToken falls back to servicePassword"() {
		given:
		AccountIntegration integration = new AccountIntegration()
		integration.servicePassword = 'pwd-token'

		expect:
		provider.resolveToken(integration) == 'pwd-token'
	}

	private static LlmIntegration languageModelIntegration(AccountIntegration accountIntegration) {
		LlmIntegration integration = new LlmIntegration()
		integration.accountIntegration = accountIntegration
		if(accountIntegration?.id) {
			integration.id = accountIntegration.id
			integration.code = "language-model-${accountIntegration.id}"
		}
		return integration
	}
}
