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

import com.morpheusdata.model.llm.LlmChatMessage
import com.morpheusdata.model.llm.LlmChatResponse
import com.morpheusdata.model.llm.LlmTokenUsage
import com.morpheusdata.response.LlmStreamingResponseHandler
import groovy.json.JsonBuilder
import groovy.json.JsonSlurper
import groovy.util.logging.Slf4j

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

	/**
	 * List available models from GitHub Copilot.
	 */
	Map listModels(String baseUrl, String token) {
		return executeGet("${baseUrl}${MODELS_PATH}", token)
	}

	/**
	 * Execute a non-streaming interaction request against a model endpoint.
	 */
	Map interact(String baseUrl, String token, String endpointPath, Map requestBody) {
		String path = endpointPath ?: DEFAULT_INTERACTION_PATH
		return executePost("${baseUrl}${path}", token, requestBody)
	}

	/**
	 * Execute a non-streaming responses API request.
	 */
	Map responsesCompletion(String baseUrl, String token, Map requestBody) {
		return interact(baseUrl, token, RESPONSES_PATH, requestBody)
	}

	/**
	 * Execute a streaming interaction request via SSE.
	 */
	void streamInteract(String baseUrl, String token, String endpointPath, Map requestBody, LlmStreamingResponseHandler handler) {
		HttpURLConnection connection = null
		try {
			requestBody.stream = true
			String jsonBody = new JsonBuilder(requestBody).toString()
			String path = endpointPath ?: DEFAULT_INTERACTION_PATH

			URL url = new URL("${baseUrl}${path}")
			connection = (HttpURLConnection) url.openConnection()
			connection.requestMethod = 'POST'
			connection.setRequestProperty('Content-Type', 'application/json')
			connection.setRequestProperty('Authorization', "Bearer ${token}")
			connection.setRequestProperty(COPILOT_INTEGRATION_HEADER, COPILOT_INTEGRATION_ID)
			connection.setRequestProperty('Accept', 'text/event-stream')
			connection.doOutput = true
			connection.connectTimeout = 30000
			connection.readTimeout = 120000

			connection.outputStream.withWriter('UTF-8') { writer ->
				writer.write(jsonBody)
			}

			int responseCode = connection.responseCode
			if (responseCode != 200) {
				String errorBody = connection.errorStream?.text ?: 'Unknown error'
				handler?.onError(new RuntimeException("Copilot API returned ${responseCode}: ${errorBody}"))
				return
			}

			StringBuilder fullContent = new StringBuilder()
			String responseModel = null
			String finishReason = null

			connection.inputStream.withReader('UTF-8') { reader ->
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

			LlmChatResponse response = new LlmChatResponse()
			response.model = responseModel
			response.finishReason = finishReason
			LlmChatMessage msg = new LlmChatMessage()
			msg.role = 'assistant'
			msg.content = fullContent.toString()
			response.message = msg
			handler?.onCompleteResponse(response)

		} catch (Exception e) {
			log.error("Error during streaming chat completion: ${e.message}", e)
			handler?.onError(e)
		} finally {
			connection?.disconnect()
		}
	}

	/**
	 * Execute a GET request against the Copilot API.
	 */
	protected Map executeGet(String urlString, String token) {
		HttpURLConnection connection = null
		try {
			URL url = new URL(urlString)
			connection = (HttpURLConnection) url.openConnection()
			connection.requestMethod = 'GET'
			connection.setRequestProperty('Authorization', "Bearer ${token}")
			connection.setRequestProperty(COPILOT_INTEGRATION_HEADER, COPILOT_INTEGRATION_ID)
			connection.setRequestProperty('Accept', 'application/json')
			connection.connectTimeout = 30000
			connection.readTimeout = 30000

			int responseCode = connection.responseCode
			if (responseCode == 200) {
				String responseBody = connection.inputStream.text
				Map data = new JsonSlurper().parseText(responseBody) as Map
				return [success: true, data: data]
			} else {
				String errorBody = connection.errorStream?.text ?: 'Unknown error'
				log.warn("Copilot API GET ${urlString} returned ${responseCode}: ${errorBody}")
				return [success: false, msg: "API returned ${responseCode}: ${errorBody}"]
			}
		} catch (Exception e) {
			log.error("Error executing GET ${urlString}: ${e.message}", e)
			return [success: false, msg: e.message]
		} finally {
			connection?.disconnect()
		}
	}

	/**
	 * Execute a POST request against the Copilot API.
	 */
	protected Map executePost(String urlString, String token, Map requestBody) {
		HttpURLConnection connection = null
		try {
			String jsonBody = new JsonBuilder(requestBody).toString()

			URL url = new URL(urlString)
			connection = (HttpURLConnection) url.openConnection()
			connection.requestMethod = 'POST'
			connection.setRequestProperty('Content-Type', 'application/json')
			connection.setRequestProperty('Authorization', "Bearer ${token}")
			connection.setRequestProperty(COPILOT_INTEGRATION_HEADER, COPILOT_INTEGRATION_ID)
			connection.setRequestProperty('Accept', 'application/json')
			connection.doOutput = true
			connection.connectTimeout = 30000
			connection.readTimeout = 120000

			connection.outputStream.withWriter('UTF-8') { writer ->
				writer.write(jsonBody)
			}

			int responseCode = connection.responseCode
			if (responseCode == 200) {
				String responseBody = connection.inputStream.text
				Map data = new JsonSlurper().parseText(responseBody) as Map
				return [success: true, data: data]
			} else {
				String errorBody = connection.errorStream?.text ?: 'Unknown error'
				log.warn("Copilot API POST ${urlString} returned ${responseCode}: ${errorBody}")
				return [success: false, msg: "API returned ${responseCode}: ${errorBody}"]
			}
		} catch (Exception e) {
			log.error("Error executing POST ${urlString}: ${e.message}", e)
			return [success: false, msg: e.message]
		} finally {
			connection?.disconnect()
		}
	}
}
