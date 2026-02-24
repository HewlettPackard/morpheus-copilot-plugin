# Morpheus GitHub Copilot Plugin

`morpheus-copilot-plugin` is a Morpheus plugin that adds a **GitHub Copilot** language model provider for the Morpheus LLM Engine.

The provider uses GitHub Copilot's OpenAI-compatible API to support:
- chat completions
- streaming chat completions
- embeddings

## Compatibility

- **Morpheus minimum appliance version:** `9.0.0`
- **Java:** `11`
- **Plugin version:** `1.0.0-SNAPSHOT`

## What this plugin does

- Registers a Morpheus `LanguageModelProvider` named **GitHub Copilot**
- Verifies connectivity using your GitHub token
- Syncs available models from Copilot and persists them in Morpheus
- Supports chat and embedding model families exposed by Copilot (for example GPT, Claude, and Gemini families, plus text-embedding models)

## Configuration

When creating the integration in Morpheus:

- **Provider:** `GitHub Copilot`
- **Service URL:** defaults to `https://api.githubcopilot.com`
- **Credentials:** API key / GitHub token (required)

If a request does not specify a model:
- Default chat model: `gpt-4o`
- Default embedding model: `text-embedding-3-small`

## Build

```bash
./gradlew clean shadowJar
```

The distributable plugin JAR is created under `build/libs/`.

## Test

```bash
./gradlew clean test
```

## Install in Morpheus

Use your normal Morpheus plugin installation workflow to upload the built JAR, then create an LLM integration using the **GitHub Copilot** provider and your GitHub token.

## Development notes

- Main plugin classes: `src/main/groovy/com/morpheusdata/copilot/`
- Unit tests: `src/test/groovy/com/morpheusdata/copilot/CopilotProviderSpec.groovy`

## License

Apache License 2.0
