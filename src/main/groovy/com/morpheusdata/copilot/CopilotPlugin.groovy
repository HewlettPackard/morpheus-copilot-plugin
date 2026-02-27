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

import com.morpheusdata.core.Plugin
import groovy.util.logging.Slf4j

/**
 * Plugin entrypoint for GitHub Copilot LLM Engine integration.
 * Registers the CopilotProvider which implements LlmProvider.
 *
 * @author Morpheus
 */
@Slf4j
class CopilotPlugin extends Plugin {

	@Override
	String getCode() {
		return 'morpheus-copilot-plugin'
	}

	@Override
	void initialize() {
		this.setName('GitHub Copilot')
		CopilotProvider copilotProvider = new CopilotProvider(this, morpheus)
		this.pluginProviders.put(copilotProvider.code, copilotProvider)
	}

	@Override
	void onDestroy() {
		// cleanup if needed
	}
}
