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

import com.morpheusdata.model.llm.LlmIntegration
import groovy.util.logging.Slf4j

import java.time.Instant

@Slf4j
class LlmUsageSync {

	protected final LlmIntegration llmIntegration

	LlmUsageSync(LlmIntegration llmIntegration) {
		this.llmIntegration = llmIntegration
	}

	void execute(Map headers) {
		if(!llmIntegration) {
			return
		}
		Map<String, Map<String, Object>> usageMetrics = buildUsageMetrics(headers)
		if(!usageMetrics) {
			return
		}
		Map<String, Object> tokenUsage = usageMetrics.tokenUsage ?: [:]
		if(tokenUsage.containsKey('used')) {
			llmIntegration.tokenUsageUsed = parseLongValue(tokenUsage.used)
		}
		if(tokenUsage.containsKey('remaining')) {
			llmIntegration.tokenUsageRemaining = parseLongValue(tokenUsage.remaining)
		}
		if(tokenUsage.containsKey('limit')) {
			llmIntegration.tokenUsageLimit = parseLongValue(tokenUsage.limit)
		}
		if(tokenUsage.containsKey('period')) {
			llmIntegration.tokenUsagePeriod = tokenUsage.period?.toString()
		}
		if(tokenUsage.containsKey('resetAt')) {
			llmIntegration.tokenUsageResetAt = tokenUsage.resetAt?.toString()
		}

		Map<String, Object> requestUsage = usageMetrics.requestUsage ?: [:]
		if(requestUsage.containsKey('used')) {
			llmIntegration.requestUsageUsed = parseLongValue(requestUsage.used)
		}
		if(requestUsage.containsKey('remaining')) {
			llmIntegration.requestUsageRemaining = parseLongValue(requestUsage.remaining)
		}
		if(requestUsage.containsKey('limit')) {
			llmIntegration.requestUsageLimit = parseLongValue(requestUsage.limit)
		}
		if(requestUsage.containsKey('period')) {
			llmIntegration.requestUsagePeriod = requestUsage.period?.toString()
		}
		if(requestUsage.containsKey('resetAt')) {
			llmIntegration.requestUsageResetAt = requestUsage.resetAt?.toString()
		}
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
}
