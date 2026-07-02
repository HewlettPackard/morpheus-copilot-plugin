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

import com.morpheusdata.core.util.HttpApiClient
import com.morpheusdata.model.llm.LlmChatMessage
import com.morpheusdata.model.llm.LlmChatResponse
import com.morpheusdata.response.ServiceResponse
import com.morpheusdata.response.LlmStreamingResponseHandler
import groovy.json.JsonSlurper
import groovy.util.logging.Slf4j
import org.apache.http.client.methods.CloseableHttpResponse
import org.apache.http.util.EntityUtils

import java.util.concurrent.ConcurrentHashMap

/**
 * HTTP client service for GitHub Copilot API interactions.
 * The Copilot API is OpenAI-compatible, using the same request/response format
 * with a different base URL and GitHub token authentication.
 *
 * @author Morpheus
 */
@Slf4j
class CopilotApiService {

	static final String CHAT_COMPLETIONS_PATH = '/chat/completions'
	static final String RESPONSES_PATH = '/responses'
	static final String MODELS_PATH = '/models'
	static final String EMBEDDINGS_PATH = '/v1/embeddings'
	static final String DEFAULT_INTERACTION_PATH = CHAT_COMPLETIONS_PATH
	static final String COPILOT_INTEGRATION_HEADER = 'Copilot-Integration-Id'
	static final String COPILOT_INTEGRATION_ID = 'copilot-developer-cli'
	static final String CLIENT_SCOPE_KEY = 'clientScopeKey'
	static final Integer DEFAULT_CONNECTION_TIMEOUT = 30000
	static final Integer DEFAULT_READ_TIMEOUT = 30000
	static final Integer DEFAULT_INTERACT_READ_TIMEOUT = 120000
	static final Long SESSION_CLIENT_TTL_MS = 60L * 60L * 1000L

	protected final ConcurrentHashMap<String, SessionClientHolder> sessionClients = new ConcurrentHashMap<>()

	protected static class SessionClientHolder {
		final HttpApiClient apiClient
		volatile long lastUsedAt

		SessionClientHolder(HttpApiClient apiClient, long lastUsedAt) {
			this.apiClient = apiClient
			this.lastUsedAt = lastUsedAt
		}
	}


	/**
	 * List available models from GitHub Copilot.
	 */
	Map listModels(String baseUrl, String token) {
		return executeGet(baseUrl, MODELS_PATH, token)
	}

	/**
	 * Execute a non-streaming interaction request against a model endpoint.
	 */
	Map interact(String baseUrl, String token, String endpointPath, Map requestBody, Map opts = [:]) {
		String path = endpointPath ?: DEFAULT_INTERACTION_PATH
		return executePost(baseUrl, path, token, requestBody, opts)
	}

	/**
	 * Execute a non-streaming responses API request.
	 */
	Map responsesCompletion(String baseUrl, String token, Map requestBody, Map opts = [:]) {
		return interact(baseUrl, token, RESPONSES_PATH, requestBody, opts)
	}

	/**
	 * Execute a minimal request to retrieve current Copilot rate limit headers.
	 */
	Map fetchUsageHeaders(String baseUrl, String token, String model) {
		Map requestBody = [
			model   : model ?: 'gpt-4o',
			messages: [[role: 'user', content: 'usage']],
			stream  : false,
			max_tokens: 1
		]
		return executePost(baseUrl, CHAT_COMPLETIONS_PATH, token, requestBody)
	}

	/**
	 * Execute a streaming interaction request via SSE.
	 */
	void streamInteract(String baseUrl, String token, String endpointPath, Map requestBody, LlmStreamingResponseHandler handler, Map opts = [:]) {
		CloseableHttpResponse response = null
		try {
			requestBody.stream = true
			String path = endpointPath ?: DEFAULT_INTERACTION_PATH
			HttpApiClient.RequestOptions requestOptions = buildRequestOptions(token, [
				'Content-Type'              : 'application/json',
				'Accept'                    : 'text/event-stream',
				(COPILOT_INTEGRATION_HEADER): COPILOT_INTEGRATION_ID
			], requestBody, DEFAULT_INTERACT_READ_TIMEOUT)

			withApiClient(opts) { HttpApiClient apiClient ->
				ServiceResponse<CloseableHttpResponse> apiResponse = apiClient.callStreamApi(baseUrl, path, null, null, requestOptions, 'POST')
				response = apiResponse?.data
				if(apiResponse?.success != true || response == null) {
					String statusCode = apiResponse?.errorCode ?: response?.statusLine?.statusCode?.toString() ?: 'unknown'
					String errorBody = readErrorBody(response)
					String errorMsg = errorBody
						? (errorBody.length() > 500 ? errorBody.substring(0, 500) + "…[truncated]" : errorBody)
						: buildErrorMessage(apiResponse)
					log.warn("Copilot API error response (status={}): {}", statusCode, errorMsg)
					if(errorBody && errorBody.length() > 500 && log.isDebugEnabled()) {
						log.debug("Copilot API error response body (status={}): {}", statusCode, errorBody)
					}
					handler?.onError(new RuntimeException("Copilot API returned ${statusCode}: ${errorMsg}"))
					return
				}

				StringBuilder fullContent = new StringBuilder()
				String responseModel = null
				String finishReason = null

				response.entity?.content?.withReader('UTF-8') { reader ->
					String line
					while ((line = reader.readLine()) != null) {
						if (line.startsWith('data: ')) {
							String data = line.substring(6).trim()
							if (data == '[DONE]') {
								break
							}
							try {
								Map chunk = new JsonSlurper().parseText(data) as Map
								responseModel = responseModel ?: chunk.model?.toString()
								def choices = chunk.choices
								if (choices instanceof List && choices.size() > 0) {
									def delta = choices[0].delta
									if (delta?.content) {
										String partialText = delta.content.toString()
										fullContent.append(partialText)
										handler?.onPartialResponse(partialText)
									}
									if (choices[0].finish_reason) {
										finishReason = choices[0].finish_reason.toString()
									}
								}
							} catch (Exception parseEx) {
								log.debug("Skipping unparseable SSE chunk: ${data}")
							}
						}
					}
				}

				LlmChatResponse streamResponse = new LlmChatResponse()
				streamResponse.model = responseModel
				streamResponse.finishReason = finishReason
				LlmChatMessage msg = new LlmChatMessage()
				msg.role = 'assistant'
				msg.content = fullContent.toString()
				streamResponse.message = msg
				handler?.onCompleteResponse(streamResponse)
			}

		} catch (Exception e) {
			log.error("Error during streaming chat completion: ${e.message}", e)
			handler?.onError(e)
		} finally {
			response?.close()
		}
	}

	/**
	 * Execute a GET request against the Copilot API.
	 */
	protected Map executeGet(String baseUrl, String path, String token, Map opts = [:]) {
		try {
			HttpApiClient.RequestOptions requestOptions = buildRequestOptions(token, [
				'Accept'                    : 'application/json',
				(COPILOT_INTEGRATION_HEADER): COPILOT_INTEGRATION_ID
			], null, DEFAULT_READ_TIMEOUT)
			return withApiClient(opts) { HttpApiClient apiClient ->
				ServiceResponse apiResponse = apiClient.callJsonApi(baseUrl, path, null, null, requestOptions, 'GET')
				if(apiResponse?.success) {
					Map responseData = apiResponse?.data instanceof Map ? apiResponse.data as Map : [:]
					if(!(apiResponse?.data instanceof Map) && apiResponse?.data != null) {
						responseData.data = apiResponse.data
					}
					return [success: true, data: responseData, headers: apiResponse?.headers]
				} else {
					return [success: false, msg: buildErrorMessage(apiResponse), headers: apiResponse?.headers]
				}
			}
		} catch (Exception e) {
			log.error("Error executing GET ${baseUrl}${path}: ${e.message}", e)
			return [success: false, msg: e.message]
		}
	}

	/**
	 * Execute a POST request against the Copilot API.
	 */
	protected Map executePost(String baseUrl, String path, String token, Map requestBody, Map opts = [:]) {
		try {
			HttpApiClient.RequestOptions requestOptions = buildRequestOptions(token, [
				'Content-Type'              : 'application/json',
				'Accept'                    : 'application/json',
				(COPILOT_INTEGRATION_HEADER): COPILOT_INTEGRATION_ID
			], requestBody, DEFAULT_INTERACT_READ_TIMEOUT)
			return withApiClient(opts) { HttpApiClient apiClient ->
				ServiceResponse apiResponse = apiClient.callJsonApi(baseUrl, path, null, null, requestOptions, 'POST')
				if(apiResponse?.success) {
					Map responseData = apiResponse?.data instanceof Map ? apiResponse.data as Map : [:]
					if(!(apiResponse?.data instanceof Map) && apiResponse?.data != null) {
						responseData.data = apiResponse.data
					}
					return [success: true, data: responseData, headers: apiResponse?.headers]
				} else {
					return [success: false, msg: buildErrorMessage(apiResponse), headers: apiResponse?.headers]
				}
			}
		} catch (Exception e) {
			log.error("Error executing POST ${baseUrl}${path}: ${e.message}", e)
			return [success: false, msg: e.message]
		}
	}

	protected <T> T withApiClient(Map opts = [:], Closure<T> work) {
		evictExpiredSessionClients()
		String clientScopeKey = resolveClientScopeKey(opts)
		if(clientScopeKey) {
			long now = System.currentTimeMillis()
			SessionClientHolder sessionClient = sessionClients.compute(clientScopeKey) { String key, SessionClientHolder existing ->
				if(existing && !isExpired(existing, now)) {
					existing.lastUsedAt = now
					return existing
				}
				if(existing?.apiClient) {
					shutdownApiClient(existing.apiClient)
				}
				return new SessionClientHolder(createApiClient(true), now)
			}
			return work.call(sessionClient.apiClient)
		}

		HttpApiClient apiClient = createApiClient(false)
		try {
			return work.call(apiClient)
		} finally {
			shutdownApiClient(apiClient)
		}
	}

	protected String resolveClientScopeKey(Map opts = [:]) {
		String clientScopeKey = opts?.get(CLIENT_SCOPE_KEY)?.toString()?.trim()
		return clientScopeKey ?: null
	}

	protected HttpApiClient createApiClient(boolean sessionScoped) {
		return sessionScoped ? new HttpApiClient(true) : new HttpApiClient()
	}

	protected void shutdownApiClient(HttpApiClient apiClient) {
		apiClient?.shutdownClient()
	}

	protected void evictExpiredSessionClients() {
		long now = System.currentTimeMillis()
		sessionClients.each { String clientScopeKey, SessionClientHolder holder ->
			if(holder && isExpired(holder, now) && sessionClients.remove(clientScopeKey, holder)) {
				shutdownApiClient(holder.apiClient)
			}
		}
	}

	protected boolean isExpired(SessionClientHolder holder, long now = System.currentTimeMillis()) {
		return holder == null || (now - holder.lastUsedAt) > SESSION_CLIENT_TTL_MS
	}

	protected HttpApiClient.RequestOptions buildRequestOptions(String token, Map<CharSequence, CharSequence> headers, Object body, Integer readTimeout) {
		HttpApiClient.RequestOptions options = new HttpApiClient.RequestOptions(
			apiToken         : token,
			headers          : headers,
			ignoreSSL        : false,
			connectionTimeout: DEFAULT_CONNECTION_TIMEOUT,
			readTimeout      : readTimeout
		)
		if(body != null) {
			options.body = body
		}
		return options
	}

	protected String buildErrorMessage(ServiceResponse response) {
		if(response == null) {
			return 'Unknown error'
		}
		Map responseData = response.data instanceof Map ? response.data as Map : [:]
		String errorMessage = responseData.error?.toString() ?: responseData.message?.toString() ?: responseData.msg?.toString() ?: response.content ?: response.error ?: response.msg
		String errorCode = response.errorCode ?: response.statusCode
		if(errorCode && errorMessage) {
			return "API returned ${errorCode}: ${errorMessage}"
		}
		if(errorCode) {
			return "API returned ${errorCode}"
		}
		return errorMessage ?: 'Unknown error'
	}

	protected String readErrorBody(CloseableHttpResponse response) {
		try {
			if(response?.entity) {
				return EntityUtils.toString(response.entity)
			}
		} catch(Exception ignored) {
		}
		return null
	}
}
