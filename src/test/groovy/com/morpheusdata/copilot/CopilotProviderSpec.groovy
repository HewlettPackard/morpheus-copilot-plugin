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

import com.morpheusdata.core.MorpheusAsyncServices
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.core.integration.MorpheusLLMEngineModelService
import com.morpheusdata.model.AccountIntegration
import com.morpheusdata.model.llmEngine.*
import com.morpheusdata.response.LLMEngineStreamingResponseHandler
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.Single
import spock.lang.Specification

class CopilotProviderSpec extends Specification {

	CopilotProvider provider
	Plugin plugin
	MorpheusContext morpheusContext
	MorpheusAsyncServices asyncServices
	MorpheusLLMEngineModelService modelService
	CopilotApiService apiService

	def setup() {
		plugin = Mock(Plugin)
		morpheusContext = Mock(MorpheusContext)
		asyncServices = Mock(MorpheusAsyncServices)
		modelService = Mock(MorpheusLLMEngineModelService)

		morpheusContext.getAsync() >> asyncServices
		asyncServices.getLLMEngineModel() >> modelService
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

	def "provider returns LLMEngineIntegrationType"() {
		when:
		def type = provider.getLLMEngineIntegrationType()

		then:
		type.code == 'github-copilot'
		type.name == 'GitHub Copilot'
		type.chatSupported
		type.streamingChatSupported
		type.embeddingSupported
		type.enabled
	}

	def "provider returns integration option types with copilot defaults"() {
		when:
		def opts = provider.getIntegrationOptionTypes()

		then:
		opts != null
		opts.size() >= 1
		def urlOpt = opts.find { it.fieldName == 'serviceUrl' }
		urlOpt.defaultValue == 'https://api.githubcopilot.com'
		def tokenOpt = opts.find { it.fieldName == 'serviceToken' }
		tokenOpt.name == 'GitHub Token'
	}

	// --- Verify ---

	def "verify succeeds when API returns models"() {
		given:
		AccountIntegration integration = new AccountIntegration(
			serviceUrl: 'https://api.githubcopilot.com',
			serviceToken: 'gh-tok-123'
		)

		when:
		def result = provider.verifyAccountIntegration(integration, [:])

		then:
		1 * apiService.listModels('https://api.githubcopilot.com', 'gh-tok-123') >> [success: true, data: [data: []]]
		result.success
	}

	def "verify fails when token is missing"() {
		given:
		AccountIntegration integration = new AccountIntegration(serviceUrl: 'https://api.githubcopilot.com')

		when:
		def result = provider.verifyAccountIntegration(integration, [:])

		then:
		!result.success
		result.errors?.error?.contains('GitHub Token is required')
	}

	// --- Capabilities ---

	def "getCapabilities returns chat and streaming and embedding support"() {
		given:
		AccountIntegration integration = new AccountIntegration(
			serviceUrl: 'https://api.githubcopilot.com',
			serviceToken: 'gh-tok-123'
		)

		when:
		def result = provider.getCapabilities(integration, [:])

		then:
		1 * apiService.listModels('https://api.githubcopilot.com', 'gh-tok-123') >> [success: true, data: [data: [[id: 'gpt-4o', name: 'GPT-4o']]]]
		result.success
		result.data.chatSupported
		result.data.streamingChatSupported
		result.data.embeddingSupported
	}

	// --- Chat completion ---

	def "chatCompletion succeeds with valid response"() {
		given:
		AccountIntegration integration = new AccountIntegration(
			serviceUrl: 'https://api.githubcopilot.com',
			serviceToken: 'gh-tok-123'
		)
		LLMEngineChatRequest request = new LLMEngineChatRequest()
		request.model = 'gpt-4o'
		LLMEngineChatMessage msg = new LLMEngineChatMessage()
		msg.role = 'user'
		msg.content = 'Hello'
		request.messages = [msg]

		when:
		def result = provider.chatCompletion(integration, request, [:])

		then:
		1 * apiService.chatCompletion('https://api.githubcopilot.com', 'gh-tok-123', _) >> [
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
		AccountIntegration integration = new AccountIntegration(
			serviceUrl: 'https://api.githubcopilot.com',
			serviceToken: 'gh-tok-123'
		)
		LLMEngineChatRequest request = new LLMEngineChatRequest()
		LLMEngineChatMessage msg = new LLMEngineChatMessage()
		msg.role = 'user'
		msg.content = 'Test'
		request.messages = [msg]

		when:
		provider.chatCompletion(integration, request, [:])

		then:
		1 * apiService.chatCompletion(_, _, { Map body -> body.model == 'gpt-4o' }) >> [success: true, data: [choices: [[message: [content: 'ok']]]]]
	}

	def "chatCompletion handles API failure"() {
		given:
		AccountIntegration integration = new AccountIntegration(
			serviceUrl: 'https://api.githubcopilot.com',
			serviceToken: 'gh-tok-123'
		)
		LLMEngineChatRequest request = new LLMEngineChatRequest()
		request.model = 'gpt-4o'
		request.messages = []

		when:
		def result = provider.chatCompletion(integration, request, [:])

		then:
		1 * apiService.chatCompletion(_, _, _) >> [success: false, msg: 'Rate limited']
		!result.success
		result.errors?.error?.contains('Rate limited')
	}

	// --- Streaming ---

	def "streamChatCompletion calls apiService with stream true"() {
		given:
		AccountIntegration integration = new AccountIntegration(
			serviceUrl: 'https://api.githubcopilot.com',
			serviceToken: 'gh-tok-123'
		)
		LLMEngineChatRequest request = new LLMEngineChatRequest()
		request.model = 'claude-sonnet-4'
		LLMEngineChatMessage msg = new LLMEngineChatMessage()
		msg.role = 'user'
		msg.content = 'Stream test'
		request.messages = [msg]
		LLMEngineStreamingResponseHandler handler = Mock()

		when:
		provider.streamChatCompletion(integration, request, handler, [:])

		then:
		1 * apiService.streamChatCompletion('https://api.githubcopilot.com', 'gh-tok-123', { Map body ->
			body.model == 'claude-sonnet-4' && body.stream == true
		}, handler)
	}

	// --- Embeddings ---

	def "generateEmbeddings returns parsed embedding response"() {
		given:
		AccountIntegration integration = new AccountIntegration(
			serviceUrl: 'https://api.githubcopilot.com',
			serviceToken: 'gh-tok-123'
		)
		LLMEngineEmbeddingRequest request = new LLMEngineEmbeddingRequest()
		request.model = 'text-embedding-3-small'
		request.input = ['Hello world']

		when:
		def result = provider.generateEmbeddings(integration, request, [:])

		then:
		1 * apiService.createEmbeddings('https://api.githubcopilot.com', 'gh-tok-123', _) >> [
			success: true,
			data   : [
				model: 'text-embedding-3-small',
				data : [[embedding: [0.1, 0.2, 0.3]]],
				usage: [prompt_tokens: 3, total_tokens: 3]
			]
		]
		result.success
		result.data.embeddings.size() == 1
		result.data.embeddings[0] == [0.1d, 0.2d, 0.3d]
		result.data.tokenUsage.inputTokens == 3
	}

	// --- Model building & categorization ---

	def "buildModelsFromApiResponse parses models correctly"() {
		given:
		Map apiResponse = [
			data: [
				[id: 'gpt-4o', name: 'GPT-4o'],
				[id: 'claude-sonnet-4', name: 'Claude Sonnet 4'],
				[id: 'text-embedding-3-small', name: 'Text Embedding 3 Small'],
				[id: 'some-unknown-model', name: 'Unknown']
			]
		]

		when:
		def models = provider.buildModelsFromApiResponse(apiResponse)

		then:
		models.size() == 3  // unknown model is filtered out
		models*.code.contains('gpt-4o')
		models*.code.contains('claude-sonnet-4')
		models*.code.contains('text-embedding-3-small')
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
		AccountIntegration integration = new AccountIntegration(serviceToken: 'tok')
		Map apiResponse = [data: [[id: 'gpt-4o', name: 'GPT-4o']]]

		when:
		provider.syncModels(integration, apiResponse)

		then:
		1 * modelService.list(_) >> Observable.empty()
		1 * modelService.bulkCreate({ List models -> models.size() == 1 && models[0].code == 'gpt-4o' }) >> Single.just(true)
	}

	def "syncModels disables removed models"() {
		given:
		AccountIntegration integration = new AccountIntegration(serviceToken: 'tok')
		Map apiResponse = [data: []]
		LLMEngineModel existing = new LLMEngineModel()
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
		AccountIntegration integration = new AccountIntegration(serviceToken: 'tok')
		Map apiResponse = [data: [[id: 'gpt-4o', name: 'GPT-4o']]]
		LLMEngineModel existing = new LLMEngineModel()
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

	def "parseEmbeddingResponse parses multiple embeddings"() {
		given:
		Map data = [
			model: 'text-embedding-3-small',
			data : [
				[embedding: [0.1, 0.2, 0.3]],
				[embedding: [0.4, 0.5, 0.6]]
			],
			usage: [prompt_tokens: 8, total_tokens: 8]
		]

		when:
		def response = provider.parseEmbeddingResponse(data, 'text-embedding-3-small')

		then:
		response.model == 'text-embedding-3-small'
		response.embeddings.size() == 2
		response.tokenUsage.inputTokens == 8
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
}
