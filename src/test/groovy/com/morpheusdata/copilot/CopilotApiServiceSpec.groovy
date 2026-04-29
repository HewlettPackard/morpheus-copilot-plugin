package com.morpheusdata.copilot

import com.morpheusdata.core.util.HttpApiClient
import spock.lang.Specification

class CopilotApiServiceSpec extends Specification {

	def "withApiClient reuses the same client for the same chat scope"() {
		given:
		def service = new TestCopilotApiService()

		when:
		def first = service.captureClient([clientScopeKey: 'lease-1'])
		def second = service.captureClient([clientScopeKey: 'lease-1'])

		then:
		first.is(second)
		service.createdClients.size() == 1
		service.shutdownClients.empty
	}

	def "withApiClient isolates clients across chat scopes"() {
		given:
		def service = new TestCopilotApiService()

		when:
		def first = service.captureClient([clientScopeKey: 'lease-1'])
		def second = service.captureClient([clientScopeKey: 'lease-2'])

		then:
		!first.is(second)
		service.createdClients.size() == 2
		service.shutdownClients.empty
	}

	def "withApiClient creates and shuts down ephemeral clients when no chat scope is provided"() {
		given:
		def service = new TestCopilotApiService()

		when:
		def first = service.captureClient([:])
		def second = service.captureClient([:])

		then:
		!first.is(second)
		service.createdClients.size() == 2
		service.shutdownClients == [first, second]
	}

	private static class TestCopilotApiService extends CopilotApiService {
		final List<HttpApiClient> createdClients = []
		final List<HttpApiClient> shutdownClients = []

		HttpApiClient captureClient(Map opts = [:]) {
			HttpApiClient resolvedClient = null
			withApiClient(opts) { HttpApiClient apiClient ->
				resolvedClient = apiClient
				return null
			}
			return resolvedClient
		}

		@Override
		protected HttpApiClient createApiClient(boolean sessionScoped) {
			HttpApiClient apiClient = sessionScoped ? new HttpApiClient(true) : new HttpApiClient()
			createdClients << apiClient
			return apiClient
		}

		@Override
		protected void shutdownApiClient(HttpApiClient apiClient) {
			shutdownClients << apiClient
		}
	}
}
