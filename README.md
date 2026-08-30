# ArkLlm

An on-device local-LLM inference backend for Android, exposed as a cross-app
AIDL service. One app loads a model once; any other app binds and streams
inference over the same session — no root, no custom ROM, no per-app model
copies. Access is authorized per app: the first time an app connects, the host
asks the user for consent and remembers the decision.

Powered by [LiteRT-LM](https://github.com/google-ai-edge/LiteRT-LM) (Google AI
Edge). Works fully offline.

## What's in this repo

| Module | Package | Role |
|--------|---------|------|
| `:app` | `com.arkj.llmserver` | The **host app** — a foreground service that loads a LiteRT-LM model, owns a single warm engine, and serializes bound clients through a session queue. |
| `:llm-contract` | `com.arkj.llm.contract` | The **AIDL contract** + JSON wire codecs shared by the host and every client. |
| `:llm-host-client` | `com.arkj.llm.client` | The **client SDK** — `RemoteLlmClient` / `LlmHostConnection` that another app drops in to talk to the host. |

## How it works

```
┌───────────────┐   bind (consent-gated)     ┌──────────────────────┐
│  Your app     │ ─────────────────────────▶ │  ArkLlm Host app     │
│ RemoteLlmClient│  AIDL: ILlmService         │  LlmHostService      │
│  (client SDK)  │ ◀───────────────────────── │   └─ EngineHolder     │
└───────────────┘   streaming callbacks       │      (LiteRT-LM)      │
                                              └──────────────────────┘
```

1. The host app runs a foreground service (`dataSync`) and holds a single
   LiteRT-LM `Engine` process-wide.
2. Client apps bind through the `ILlmService` AIDL. Binding requires the
   `com.arkj.llmserver.permission.BIND_LLM_SERVICE` permission (declared by the
   client SDK), and every call is authorized per app: on first use the host
   prompts for consent (dialog when foreground, notification when background)
   and remembers the decision.
3. LiteRT-LM is single-session, so the host serializes clients through a
   `SessionDispatcher` queue (with incremental history replay on switch).

## Quick start

### 1. Install the host and pick a model

Build and install `:app`, open it, then either download a built-in model
(Gemma 4 E2B / E4B) or import your own `.litertlm` file / custom URL.

```
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### 2. Add the client SDK to your app

Via JitPack (tag the repo, then):

```kotlin
// build.gradle.kts
repositories { maven("https://jitpack.io") }

dependencies {
    implementation("com.github.Ark946.localllmhost:llm-host-client:v0.1.0")
    // :llm-host-client pulls in :llm-contract transitively
}
```

### 3. Talk to the host

```kotlin
import com.arkj.llm.client.RemoteLlmClient
import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.UserMessage

val client = RemoteLlmClient(context, systemPrompt = "You are a helpful assistant.", temperature = 0.7)

val response = client.chat(
    messages = listOf(SystemMessage.from("You are helpful."), UserMessage.from("Hello")),
    toolSpecs = emptyList(),
)
println(response.text)

client.close()
```

Streaming is available through `chatStreaming(...)` with a `StreamingListener`
(`onPartialText`, `onComplete`, `onError`).

## Models

Built-in defaults live in `LocalModelManager.AVAILABLE_MODELS` (Gemma 4 E2B and
E4B from Hugging Face `litert-community`). The runtime accepts **any** `.litertlm`
file — use the host UI's custom-URL download or SAF import to run your own model
with no code changes.

## Security model

- **Per-app consent.** Binding is open to any app that declares the
  `BIND_LLM_SERVICE` permission (`protectionLevel="normal"`), but every AIDL
  call is authorized against a persisted per-package allow/deny list. The first
  time an app calls into the service, the host prompts the user — an in-app
  dialog when the host is foreground, a system notification otherwise — and
  remembers the decision. A denied app is refused on every later call without
  re-prompting. Both states can be changed any time from the host's
  "我的 → 应用权限设置" screen.
- **No network requirement.** Inference is fully local; the only network use is
  downloading models (which you can bypass entirely via file import).

## Limitations

- **One engine, one model at a time.** The host keeps a single warm engine and
  serializes clients; switching models resets live sessions (clients are notified
  via `onSessionInvalidated`).
- **LiteRT-LM only.** The engine is LiteRT-LM 0.10.x throughout; there is no
  pluggable-backend abstraction yet. GPU/CPU are two LiteRT-LM `Backend`s, and a
  low-RAM guard forces CPU on small devices to avoid OOM.
- **Tool-call parsing is Gemma-4-tuned** (`ToolCallParser`), though raw
  JSON tool-call output is also handled.

## License

Apache License 2.0 — see [LICENSE](LICENSE).

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md).
