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
package com.morpheusdata.copilot.sync

import com.morpheusdata.copilot.CopilotApiService
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.model.llm.LlmIntegration
import groovy.util.logging.Slf4j

import java.time.Instant

@Slf4j
class LlmUsageSync {

	protected final LlmIntegration llmIntegration
	protected final MorpheusContext morpheusContext

	static final List<String> RATE_LIMIT_HEADERS = [
		'x-ratelimit-limit',
		'x-ratelimit-remaining',
		'x-ratelimit-reset',
		'x-ratelimit-resource',
		'x-ratelimit-limit-requests',
		'x-ratelimit-remaining-requests',
		'x-ratelimit-reset-requests',
		'x-ratelimit-limit-tokens',
		'x-ratelimit-remaining-tokens',
		'x-ratelimit-reset-tokens'
	]

	LlmUsageSync(LlmIntegration llmIntegration) {
		this(llmIntegration, null)
	}

	LlmUsageSync(LlmIntegration llmIntegration, MorpheusContext morpheusContext) {
		this.llmIntegration = llmIntegration
		this.morpheusContext = morpheusContext
	}

	void execute(CopilotApiService apiService, String baseUrl, String token, String model) {
		if(!llmIntegration || !apiService) {
			return
		}
		Map usageResult = apiService.fetchUsageHeaders(baseUrl, token, model) ?: [success: false, msg: 'No usage response from Copilot API']
		if(usageResult.success != true) {
			log.warn("Unable to refresh Copilot usage metrics: ${usageResult.msg ?: 'unknown error'}")
		}
		Map usageHeaders = extractRateLimitHeaders(usageResult?.headers instanceof Map ? usageResult.headers as Map : [:])
		boolean usageUpdated = false
		if(usageHeaders) {
			usageUpdated = applyHeadersUsageMetrics(usageHeaders)
		} else {
			Map responseData = usageResult?.data instanceof Map ? usageResult.data as Map : [:]
			usageUpdated = applyBodyUsageMetrics(responseData)
		}
		if(usageUpdated) {
			morpheusContext?.llm?.integration.save(llmIntegration)
		}
	}

	boolean applyHeadersUsageMetrics(Map headers) {
		if(!llmIntegration) {
			return false
		}
		Map<String, Map<String, Object>> usageMetrics = buildUsageMetrics(headers)
		if(!usageMetrics) {
			return false
		}
		boolean saveRequired = false
		Map<String, Object> tokenUsage = usageMetrics.tokenUsage ?: [:]
		if(tokenUsage.containsKey('used')) {
			saveRequired = assignIfChanged('tokenUsageUsed', parseLongValue(tokenUsage.used)) || saveRequired
		}
		if(tokenUsage.containsKey('remaining')) {
			saveRequired = assignIfChanged('tokenUsageRemaining', parseLongValue(tokenUsage.remaining)) || saveRequired
		}
		if(tokenUsage.containsKey('limit')) {
			saveRequired = assignIfChanged('tokenUsageLimit', parseLongValue(tokenUsage.limit)) || saveRequired
		}
		if(tokenUsage.containsKey('period')) {
			saveRequired = assignIfChanged('tokenUsagePeriod', tokenUsage.period?.toString()) || saveRequired
		}
		if(tokenUsage.containsKey('resetAt')) {
			saveRequired = assignIfChanged('tokenUsageResetAt', tokenUsage.resetAt?.toString()) || saveRequired
		}

		Map<String, Object> requestUsage = usageMetrics.requestUsage ?: [:]
		if(requestUsage.containsKey('used')) {
			saveRequired = assignIfChanged('requestUsageUsed', parseLongValue(requestUsage.used)) || saveRequired
		}
		if(requestUsage.containsKey('remaining')) {
			saveRequired = assignIfChanged('requestUsageRemaining', parseLongValue(requestUsage.remaining)) || saveRequired
		}
		if(requestUsage.containsKey('limit')) {
			saveRequired = assignIfChanged('requestUsageLimit', parseLongValue(requestUsage.limit)) || saveRequired
		}
		if(requestUsage.containsKey('period')) {
			saveRequired = assignIfChanged('requestUsagePeriod', requestUsage.period?.toString()) || saveRequired
		}
		if(requestUsage.containsKey('resetAt')) {
			saveRequired = assignIfChanged('requestUsageResetAt', requestUsage.resetAt?.toString()) || saveRequired
		}
		return saveRequired
	}

	protected Map<String, Map<String, Object>> buildUsageMetrics(Map headers) {
		if(!(headers instanceof Map) || headers.isEmpty()) {
			return [:]
		}
		Long requestLimit = parseLongValue(headers['x-ratelimit-limit-requests'] ?: headers['x-ratelimit-limit'])
		Long requestRemaining = parseLongValue(headers['x-ratelimit-remaining-requests'] ?: headers['x-ratelimit-remaining'])
		String requestResetAt = parseResetAt(headers['x-ratelimit-reset-requests'] ?: headers['x-ratelimit-reset'])
		String usagePeriod = headers['x-ratelimit-resource']?.toString()

		Long tokenLimit = parseLongValue(headers['x-ratelimit-limit-tokens'])
		Long tokenRemaining = parseLongValue(headers['x-ratelimit-remaining-tokens'])
		String tokenResetAt = parseResetAt(headers['x-ratelimit-reset-tokens'])

		Map<String, Map<String, Object>> metrics = [:]
		Map<String, Object> requestUsage = buildUsageSection(requestLimit, requestRemaining, requestResetAt, usagePeriod)
		Map<String, Object> tokenUsage = buildUsageSection(tokenLimit, tokenRemaining, tokenResetAt, usagePeriod)
		if(requestUsage) {
			metrics.requestUsage = requestUsage
		}
		if(tokenUsage) {
			metrics.tokenUsage = tokenUsage
		}
		return metrics
	}

	protected Map<String, Object> buildUsageSection(Long limit, Long remaining, String resetAt, String period) {
		Map<String, Object> usage = [:]
		if(limit != null && remaining != null) {
			usage.used = Math.max(limit - remaining, 0L)
		}
		if(remaining != null) {
			usage.remaining = remaining
		}
		if(limit != null) {
			usage.limit = limit
		}
		if(period) {
			usage.period = period
		}
		if(resetAt) {
			usage.resetAt = resetAt
		}
		return usage
	}

	protected boolean applyBodyUsageMetrics(Map responseData) {
		if(!(responseData instanceof Map) || responseData.isEmpty()) {
			return false
		}
		Map usage = responseData.usage instanceof Map ? responseData.usage as Map : [:]
		if(!usage) {
			return false
		}
		Long promptTokens = parseLongValue(usage.prompt_tokens ?: usage.input_tokens)
		Long completionTokens = parseLongValue(usage.completion_tokens ?: usage.output_tokens)
		Long totalTokens = parseLongValue(usage.total_tokens)
		if(totalTokens == null && (promptTokens != null || completionTokens != null)) {
			totalTokens = (promptTokens ?: 0L) + (completionTokens ?: 0L)
		}
		if(totalTokens == null) {
			return false
		}
		boolean saveRequired = false
		saveRequired = assignIfChanged('tokenUsageUsed', totalTokens) || saveRequired
		saveRequired = assignIfChanged('tokenUsageRemaining', null) || saveRequired
		saveRequired = assignIfChanged('tokenUsageLimit', null) || saveRequired
		saveRequired = assignIfChanged('tokenUsagePeriod', 'request') || saveRequired
		saveRequired = assignIfChanged('tokenUsageResetAt', null) || saveRequired
		return saveRequired
	}

	protected Long parseLongValue(def value) {
		if(value == null || value == '') {
			return null
		}
		if(value instanceof Number) {
			return (value as Number).longValue()
		}
		try {
			return value.toString().toLong()
		} catch(Exception ignored) {
			return null
		}
	}

	protected String parseResetAt(def value) {
		Long epochValue = parseLongValue(value)
		if(epochValue == null) {
			return null
		}
		try {
			if(epochValue > 100000000000L) {
				return Instant.ofEpochMilli(epochValue).toString()
			}
			return Instant.ofEpochSecond(epochValue).toString()
		} catch(Exception ignored) {
			return null
		}
	}


	protected Map<String, String> extractRateLimitHeaders(Map responseHeaders) {
		Map<String, String> headers = [:]
		if(!(responseHeaders instanceof Map) || responseHeaders.isEmpty()) {
			return headers
		}
		RATE_LIMIT_HEADERS.each { String headerName ->
			Map.Entry matchedHeader = responseHeaders.entrySet().find { entry ->
				entry?.key?.toString()?.equalsIgnoreCase(headerName) && entry?.value != null && entry.value.toString() != ''
			}
			if(matchedHeader) {
				headers[headerName] = matchedHeader.value.toString()
			}
		}
		return headers
	}

	protected boolean assignIfChanged(String propertyName, Object value) {
		if(llmIntegration?."${propertyName}" != value) {
			llmIntegration?."${propertyName}" = value
			return true
		}
		return false
	}
}
