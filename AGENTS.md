# Repository Guidelines

## Project Overview

RikkaHub is a native Android LLM chat client that supports switching between different AI providers
for conversations.
Built with Jetpack Compose, Kotlin, and follows Material 3 Expressive (MD3 Expressive) design language.


## P14 Remote Client Boundary

- The canonical P14 Android client lives in this repository on branch `p14/remote`; do not create a standalone P14 Android app in `tevriqorg/P14-Entry`.
- `tevriqorg/P14-Entry` owns P14 requirements, protocol, Server implementation, and the single P14R3 recovery authority.
- Before changing P14 code, fresh-read `tevriqorg/P14-Entry#51` and the central `requirements/P14R3_RECOVERY_MATRIX.json`; choose the exact existing recovery Row ID(s). Chat history and implementation convenience are not authority.
- **Original Design Readback required before EACH major feature:** fresh-read `tevriqorg/P14-Entry#51`, the relevant original `P14R3.md`, Architecture Addendum, Remote Channel, Server/Android Design and carried-forward P14R2/Profile contract sections, not just the matrix. Document exact source sections and actual code paths in the one recovery PR/#51. Missing user-facing semantics are RED even when a DTO or CI exists. **At the end of each major task, report the drift check to the human and STOP before the next major task.**
- Every new P14 recovery commit after governance activation must carry a `P14-Row: ROW-ID[, ROW-ID]` trailer. Recovery PRs must declare `P14-Recovery-Rows`, `P14-Scope-Change: no`, and `P14-Acceptance-Evidence`. CI validates these against the central matrix.
- A missing requirement must not be silently added in Android. Scope changes happen first in the human-approved P14 authority, then the central matrix `scope_revision` changes.
- GitHub Actions is the build/test authority. VPS hosts may package/sign an already-built APK but must not compile Android source.
- CI APK artifacts are not release/install authority. Human-test and formal P14 APKs must be signed with the fixed P14 signing certificate documented in `docs/P14_REMOTE_GOVERNANCE.md`.
- Do not publish automatic debug prereleases. Only explicit stable GitHub Releases are eligible for P20 delivery.
- Preserve application/package identity for the P14 test track; do not silently create a second launcher/applicationId.

## Design Language

The UI follows Material 3 Expressive. New and reworked screens should match it rather than the older baseline
Material 3 look (cards with dividers, plain forms).

- The app theme is `MaterialExpressiveTheme` (app/src/main/java/me/rerere/rikkahub/ui/theme/Theme.kt); prefer the
  expressive variants of components, shapes (`MaterialShapes`), emphasized typography (e.g. `titleSmallEmphasized`)
  and motion from `androidx.compose.material3` over hand-rolled equivalents.
- Settings-style and form pages use segmented groups via `CardGroup` (
  app/src/main/java/me/rerere/rikkahub/ui/components/ui/CardGroup.kt) instead of a card with dividers.
- Favor larger corner radii, filled/tonal containers, and whole-row click targets.

## Build, Test, and Development Commands

```bash
./gradlew assembleDebug          # 构建 Debug APK
./gradlew test                   # 运行所有模块的 JVM 单元测试
./gradlew lint                   # 运行 Android Lint
```

## Module Structure

- **app**: Main application module with UI, ViewModels, and core logic
- **ai**: AI SDK abstraction layer for different providers (OpenAI, Google, Anthropic)
- **common**: Common utilities and extensions
- **document**: Document parsing module for handling PDF, DOCX, PPTX, and EPUB files
- **highlight**: Code syntax highlighting implementation
- **material3**: Material color utility extensions used by the app UI
- **ui**: Reusable Compose UI components that do not depend on app logic
- **search**: Search functionality SDK for multiple providers (Exa, Tavily, Zhipu, Bing, Brave, SearXNG, and others)
- **speech**: Speech module for TTS and ASR implementations
- **web**: Embedded web server module that provides Ktor server startup function and hosts static frontend build files (
  built from web-ui/ React project)
- **workspace**: Sandboxed per-workspace file system and shell execution environment exposed to the AI as tools.

## Concepts

- **Assistant**: An assistant configuration with system prompts, model parameters, and conversation isolation. Each
  assistant maintains its own settings including temperature, context size, custom headers, tools, memory options, regex
  transformations, and prompt injections (mode/lorebook). Assistants provide isolated chat environments with specific
  behaviors and capabilities. (app/src/main/java/me/rerere/rikkahub/data/model/Assistant.kt)

- **Conversation**: A persistent conversation thread between the user and an assistant. Each conversation maintains a
  list of MessageNodes in a tree structure to support message branching, along with metadata like title, creation time,
  update time, pin status, chat suggestions, optional conversation-level system prompt, and prompt injection bindings.
  Once a conversation is persisted it also holds a `ConversationConfig` snapshot (chat model, reasoning level, search,
  MCP servers, workspace, skills) taken from the assistant; from then on chat-page changes to those settings stay on
  the conversation, and code should read them through `Settings.getAssistantOf(conversation)` /
  `Settings.getChatModelOf(conversation)` instead of the assistant directly. (
  app/src/main/java/me/rerere/rikkahub/data/model/Conversation.kt,
  app/src/main/java/me/rerere/rikkahub/data/model/ConversationConfig.kt)

- **UIMessage**: A platform-agnostic message abstraction that encapsulates chat messages with different types of content
  parts (text, images, documents, reasoning, tool calls/results, etc.). Each message has a role (USER, ASSISTANT,
  SYSTEM, TOOL), creation timestamp, model ID, token usage information, and optional annotations. UIMessages support
  streaming updates through chunk merging. (ai/src/main/java/me/rerere/ai/ui/Message.kt)

- **MessageNode**: A container holding one or more UIMessages to implement message branching functionality. Each node
  maintains a list of alternative messages and tracks which message is currently selected (selectIndex). This enables
  users to regenerate responses and switch between different conversation branches, creating a tree-like conversation
  structure. (app/src/main/java/me/rerere/rikkahub/data/model/Conversation.kt)

- **Message Transformer**: A pipeline mechanism for transforming messages before sending to AI providers (
  InputMessageTransformer) or after receiving responses (OutputMessageTransformer). Transformers can modify message
  content, add metadata, apply templates, handle special tags, convert formats, and perform OCR. Common transformers
  include:
  - TemplateTransformer: Apply Pebble templates to user messages with variables like time/date
  - ThinkTagTransformer: Extract `<think>` tags and convert to reasoning parts
  - RegexOutputTransformer: Apply regex replacements to assistant responses
  - DocumentAsPromptTransformer: Convert document attachments to text prompts
  - Base64ImageToLocalFileTransformer: Convert base64 images to local file references
  - OcrTransformer: Perform OCR on images to extract text

  Output transformers support `visualTransform()` for UI display during streaming and `onGenerationFinish()` for final
  processing after generation completes.
  (app/src/main/java/me/rerere/rikkahub/data/ai/transformers/Transformer.kt)

## Internationalization

- String resources are usually located in `app/src/main/res/values*/strings.xml`; feature modules such as `search`
  may also maintain their own `values*/strings.xml`
- Use `stringResource(R.string.key_name)` in Compose
- Page-specific strings should use page prefix (e.g., `setting_page_`)
- If the user does not explicitly request localization, prioritize implementing functionality without considering
  localization. (e.g `Text("Hello world")`)
- For `locale-tui` operations, use the `locale-tui-localization` skill.
