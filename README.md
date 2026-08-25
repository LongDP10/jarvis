# JARVIS — Trợ lý AI giọng nói cho Android

Trợ lý cá nhân điều khiển bằng giọng nói, tối ưu cho Samsung Galaxy S24 Ultra.
Nghe lệnh tiếng Việt hoặc tiếng Anh, hiểu ý định, **thực sự thao tác trên điện
thoại**, rồi trả lời bằng giọng nói — với một orb nổi hiển thị trạng thái ngay cả
khi bạn đang ở trong ứng dụng khác.

---

## 1. Chạy thử

```bash
./gradlew assembleDebug
```

Yêu cầu môi trường (máy bạn đã có đủ):

| Thành phần | Phiên bản |
|---|---|
| JDK | 21 (`C:\Program Files\Android\Android Studio1\jbr`) |
| Gradle | 8.13 (wrapper đã kèm sẵn) |
| Android Gradle Plugin | 8.7.3 |
| Kotlin | 2.0.21 |
| compileSdk / targetSdk | 35 |
| minSdk | 30 |

`local.properties` đã trỏ sẵn `sdk.dir` và `org.gradle.java.home`. File này
git-ignored nên nếu chuyển máy cần tạo lại.

### Sau khi cài lên máy

1. Mở app → màn hình **Onboarding** liệt kê từng quyền kèm lý do.
2. Cấp **Micro** và **Thông báo** (dialog runtime).
3. Bật **Hiển thị trên ứng dụng khác** → cần cho orb nổi.
4. Bật **Dịch vụ trợ năng** trong Settings hệ thống. *Android không cho phép ứng
   dụng tự bật quyền này* — đây là giới hạn của hệ điều hành, không phải thiếu sót
   của app. Không có quyền này JARVIS vẫn mở được ứng dụng, chỉnh âm lượng, đèn
   pin… nhưng **không** chạm/cuộn/đọc màn hình/chụp màn hình được.
5. Vào **Settings → AI**. Provider mặc định là **Ollama** — nhập địa chỉ máy chủ
   rồi bấm **Kiểm tra kết nối** (xem mục 3 bên dưới). Nếu muốn dùng cloud thì chọn
   Gemini/OpenAI và dán API key; key được mã hoá bằng Android keystore, không nằm
   trong source code.
6. Tuỳ chọn: miễn trừ tối ưu pin. One UI của Samsung rất mạnh tay với service
   nền — không miễn trừ thì orb và wake word sẽ bị kill sau vài phút.

---

## 2. Kiến trúc

Một module `:app`, phân tầng theo package.

```
audio/text
   │
   ▼
LocalIntentMatcher ──khớp──►  ToolRegistry ──► CommandExecutor ──► Android
   │                                                  ▲
   │ không khớp                                       │
   ▼                                                  │
AIProvider (vòng lặp tool calling, tối đa 6 vòng) ─────┘
   │
   ▼
TextToSpeech
```

| Package | Vai trò |
|---|---|
| `core/` | `JarvisStateMachine` — nguồn sự thật duy nhất về trạng thái, dùng chung giữa app và orb nổi |
| `voice/` | STT, TTS, VAD, wake word, nhận diện ngôn ngữ |
| `ai/` | `AIProvider` + Ollama/Claude/Gemini/OpenAI, `AgentLoop`, `LocalIntentMatcher` |
| `commands/` | ~30 tool, registry, executor, `ConfirmationGate` |
| `accessibility/` | Service + controller: chạm, vuốt, cuộn, đọc màn hình, chụp màn hình |
| `overlay/` | Foreground service, `ComposeOverlayHost`, orb vẽ bằng Compose Canvas |
| `data/` | Room (lịch sử, log), DataStore (cài đặt), EncryptedSharedPreferences (key) |
| `ui/` | Home, Chat, Settings, History, Debug Console, Onboarding |

**Vì sao hybrid?** Khoảng 40 lệnh thường gặp nhất (`mở YouTube`, `tăng âm lượng`,
`chụp màn hình`, `về màn hình chính`…) được xử lý hoàn toàn trên máy: không mạng,
không token, không độ trễ. Mọi thứ phức tạp hơn mới đi qua model.

**Vì sao có vòng lặp agent?** Đây là thứ làm cho lệnh nhiều bước chạy thật. Model
gọi tool → kết quả được trả ngược lại → model đọc nội dung màn hình rồi mới quyết
định bấm vào đâu. "Mở video đầu tiên" hoạt động vì model *nhìn thấy* danh sách,
không phải vì có heuristic đoán mò.

---

## 3. Ollama — cấu hình mặc định

JARVIS mặc định chạy bằng **Ollama trên máy bạn**: không API key, không tốn tiền,
và không câu nào rời khỏi mạng nhà.

### Trên PC

```bash
ollama pull qwen2.5
OLLAMA_HOST=0.0.0.0 ollama serve
```

`OLLAMA_HOST=0.0.0.0` là bắt buộc. Mặc định Ollama chỉ nghe trên `127.0.0.1`, tức
là chỉ chính máy đó gọi được — điện thoại sẽ không kết nối nổi. Đây là lỗi hay gặp
nhất.

Lấy IP LAN của PC (`ipconfig` trên Windows), ví dụ `192.168.1.23`.

### Trên điện thoại

```
Settings → AI → Ollama (local)
  Địa chỉ máy chủ: http://192.168.1.23:11434
  Mô hình:         qwen2.5
  → bấm "Kiểm tra kết nối"
```

Nút kiểm tra gọi `/api/tags` và phân biệt rõ ba tình huống hay bị nhầm thành một:

| Kết quả | Nghĩa là |
|---|---|
| 🟢 *Đã kết nối. Mô hình có sẵn: …* | Xong, dùng được |
| 🟡 *Đã kết nối, nhưng máy chủ chưa pull mô hình nào* | Sai ở PC: chạy `ollama pull` |
| 🟡 *Cảnh báo: "X" không có trên máy chủ đó* | Tên model trong Settings không khớp |
| 🔴 *Không kết nối được tới …* | Sai IP, khác mạng Wi-Fi, hoặc quên `OLLAMA_HOST=0.0.0.0` |

### Chọn model

Model **bắt buộc phải hỗ trợ tool calling**, nếu không JARVIS chỉ trả lời bằng lời
mà không bấm được gì. `OllamaMapper` phát hiện trường hợp này và nói thẳng ra thay
vì đứng im.

| Model | Nhận xét |
|---|---|
| `qwen2.5` | **Mặc định.** Tiếng Việt tốt nhất trong nhóm, tool calling ổn định |
| `llama3.1` | Tool calling tốt, tiếng Việt yếu hơn rõ rệt |
| `mistral`, `gemma2` | Tiếng Việt kém và/hoặc tool calling không ổn — không khuyến nghị |

### Hai thứ đã phải sửa để Ollama chạy được

Đây không phải chi tiết vụn — thiếu một trong hai là hỏng hoàn toàn:

1. **Cleartext HTTP.** Từ targetSdk 28 Android chặn `http://`. Không có
   `network_security_config.xml` thì mọi request tới Ollama chết ngay với
   `CLEARTEXT communication not permitted`. Cleartext được mở ở mức app, **nhưng**
   `api.openai.com` và `generativelanguage.googleapis.com` bị khoá cứng HTTPS-only
   để API key không bao giờ có thể bị hạ cấp xuống plaintext. Không thể thu hẹp hơn:
   Android khớp `<domain>` theo nhãn DNS, không hỗ trợ dải CIDR, mà địa chỉ Ollama
   thì do bạn nhập lúc chạy.
2. **Kiểm tra "offline".** `AgentLoop` trước đây từ chối chạy khi
   `NET_CAPABILITY_VALIDATED` = false. Wi-Fi nhà bạn có Ollama nhưng rớt mạng ngoài
   sẽ bị Android coi là offline → JARVIS báo "cần Internet" dù server LAN vẫn tới
   được. Giờ điều kiện hỏi **provider** (`AIProvider.requiresInternet`) chứ không
   hỏi Internet. Lọc tool vẫn theo mạng thật, vì `search_web` đúng là không chạy
   được khi không có đường ra.

Timeout đọc cho Ollama là **180 giây** (cloud là 60): model local trên CPU nạp
trọng số lần đầu có thể lâu hơn nhiều so với một API đám mây.

### Còn hoạt động khi mất Internet hoàn toàn?

Có, nếu Ollama ở cùng Wi-Fi:

| Chức năng | Mất Internet, còn LAN |
|---|---|
| ~40 lệnh nhanh (`LocalIntentMatcher`) | ✅ Không cần cả LAN |
| Lệnh phức tạp, nhiều bước qua model | ✅ Qua Ollama |
| Nhận dạng giọng nói | ✅ Nếu đã tải gói ngôn ngữ offline (Settings → Local) |
| TTS | ✅ Nếu giọng đã tải về máy |
| `search_web`, `get_location` | ❌ Bị loại khỏi danh sách tool, model biết và không gọi |

---

## 4. Các provider AI

Bốn backend sau cùng một interface `AIProvider`. Đổi trong `Settings → AI`, không cần build lại.

| Provider | Model mặc định | Cần | Ghi chú |
|---|---|---|---|
| **Ollama** | `qwen2.5` | Địa chỉ LAN | Mặc định. Miễn phí, chạy cục bộ, không cần Internet |
| **Anthropic Claude** | `claude-opus-5` | API key | Tool calling mạnh nhất cho lệnh nhiều bước |
| **Google Gemini** | `gemini-2.0-flash` | API key | Free tier rộng |
| **OpenAI** | `gpt-4o-mini` | API key | |

### Claude — vài điểm riêng

Dùng raw HTTP như ba provider kia, không dùng `com.anthropic:anthropic-java`. Lý do:
SDK kéo Jackson vào APK vốn đã dùng kotlinx.serialization, và sẽ là provider duy nhất
có phần map không unit-test được theo cách ba cái còn lại đang test.

Ba khác biệt so với OpenAI/Gemini, mỗi cái sai là request bị từ chối:

| | Claude |
|---|---|
| System prompt | Trường `system` **top-level**, không phải message role system |
| Tool result | Block `tool_result` trong **lượt user**, phải khớp `tool_use_id` |
| Bị từ chối | **HTTP 200** + `stop_reason: "refusal"`, không phải mã lỗi |

Điểm cuối quan trọng: code đọc thẳng `content[0]` sẽ tưởng đó là câu trả lời rỗng.
`AnthropicMapper` kiểm tra `stop_reason` **trước khi** đọc `content`, và bật sẵn
`fallbacks: "default"` — khi bộ phân loại an toàn từ chối, Anthropic tự chạy lại
trên model phù hợp thay vì trả về lời từ chối.

`output_config.effort` đặt `"low"`, không tắt thinking. Tắt thinking trên Opus 5 là
một cái bẫy đã ghi trong tài liệu: model bắt đầu viết tool call vào **text hiển thị**
thay vì phát ra block `tool_use` — lượt đó vẫn "thành công", lệnh không bao giờ chạy,
không có lỗi nào được báo. Hạ effort vừa tránh được bẫy đó, vừa giảm độ trễ, vừa cho
câu xác nhận ngắn gọn hơn — đúng thứ một trợ lý giọng nói cần.

### Lỗi phát hiện khi thêm Claude

Đọc wire format của Anthropic làm lộ một lỗi có sẵn: `ConversationRepository`
**không lưu tool call của assistant**, chỉ lưu text và kết quả.

Cả ba provider đều yêu cầu tool call phải có mặt trong lịch sử để kết quả gắn vào:
Anthropic từ chối `tool_result` có `tool_use_id` mà nó chưa từng phát ra, OpenAI từ
chối message role `tool` không đi sau assistant message có `tool_calls`. Nghĩa là
**OpenAI đã hỏng sẵn với mọi lệnh nhiều bước** — vòng lặp agent chết ở lượt thứ hai.

Đã sửa: `ChatMessage.toolCalls` được lưu xuống Room (schema v2), `AgentLoop` ghi lượt
assistant kèm tool call **trước khi** chạy chúng, và cả bốn mapper phát lại đúng
định dạng riêng. Có test hồi quy cho từng provider.

Schema v2 dùng `fallbackToDestructiveMigration`, nên lịch sử hội thoại cũ sẽ bị xoá
ở lần chạy đầu sau khi cập nhật.

---

## 5. Bảng năng lực trung thực

Nguyên tắc xuyên suốt: **không bao giờ báo thành công cho việc Android không cho
làm.** `ToolResult` phân biệt rõ `Failure` (đã thử, không được) với `NotSupported`
(hệ điều hành cấm) — và JARVIS nói ra sự khác biệt đó.

### ✅ Hoạt động thật, API công khai

| Tool | Cơ chế |
|---|---|
| `open_app`, `list_installed_apps` | PackageManager + `AppMatcher` (không hard-code package name) |
| `search_web`, `open_url`, `open_settings`, `open_camera`, `open_gallery` | Intent |
| `go_home`, `go_back`, `open_recents`, `open_notifications`, `open_quick_settings` | Accessibility global action |
| `take_screenshot` | `AccessibilityService.takeScreenshot()` — không cần MediaProjection, không hiện dialog |
| `set_volume`, `increase_volume`, `decrease_volume`, `mute`, `get_volume` | AudioManager |
| `play_media`, `pause_media`, `next_track`, `previous_track` | `dispatchMediaKeyEvent` |
| `toggle_flashlight` | `CameraManager.setTorchMode` |
| `tap`, `long_press`, `swipe`, `scroll`, `click_text`, `input_text`, `read_screen` | Accessibility `dispatchGesture` + node tree |
| `get_time`, `get_battery_level`, `get_location`, `read_notifications`, `lookup_contact` | API tương ứng |

### ⚙️ Hoạt động thật nhưng cần quyền đặc biệt

| Tool | Quyền |
|---|---|
| `set_brightness` | `WRITE_SETTINGS` (màn hình cài đặt riêng) |
| `read_notifications` | Notification access (màn hình cài đặt riêng) |

### 🔒 Luôn phải xác nhận, không có đường vòng

| Tool | Cơ chế |
|---|---|
| `make_call` | `ConfirmationGate` chặn, hiện dialog với tên người nhận |
| `send_sms` | `ConfirmationGate` chặn, hiện **toàn bộ nội dung tin nhắn** |

Cờ `isDangerous` đọc từ `ToolSpec` đã đăng ký, **không** đọc từ dữ liệu model gửi
lên. Model không thể tự khai báo "lệnh này đã được duyệt".

### ⚠️ Android cấm — JARVIS nói thẳng và mở đúng màn hình

| Tool | Giới hạn thật | Phương án thay thế |
|---|---|---|
| `toggle_wifi` | `setWifiEnabled` bị vô hiệu với app bên thứ ba **từ Android 10** | Mở `Settings.Panel.ACTION_INTERNET_CONNECTIVITY` — panel trượt lên, bạn bấm 1 nhát |
| `toggle_bluetooth` | `BluetoothAdapter.enable()` bị chặn **từ Android 13** | Dialog hệ thống `ACTION_REQUEST_ENABLE` (bật); Bluetooth settings (tắt) |
| `close_app` | Không có API công khai để kill app khác | Về màn hình chính, và **nói rõ** là app vẫn chạy nền |

Nếu bạn thấy JARVIS nói "đã bật Wi-Fi" thì đó là bug — hãy báo lại.

---

## 6. Song ngữ

- **Tiếng Việt / English / Auto detect** trong Settings.
- Auto detect chấm điểm dựa trên dấu tiếng Việt **và** từ vựng không dấu — vì bộ
  nhận dạng giọng nói lẫn bàn phím đều thường trả về tiếng Việt không dấu.
- `LocalizedStrings` tạo context theo locale riêng, nên máy tiếng Anh + JARVIS
  tiếng Việt vẫn ra tiếng Việt (dùng `getString` thông thường sẽ sai ở đây).
- Khớp tên ứng dụng và tìm nút trên màn hình đều bỏ dấu: `"may anh"` tìm ra
  `"Máy ảnh"`, `"tim kiem"` bấm được nút `"Tìm kiếm"`.

## 7. Wake word

"Hey Jarvis" / "Jarvis", **mặc định TẮT**.

Triển khai bằng vòng lặp `SpeechRecognizer`: không cần key, không cần thư viện
ngoài, chạy offline được nếu đã tải gói ngôn ngữ. Đánh đổi thật là **tốn pin hơn
đáng kể** so với keyword spotter chuyên dụng — Settings ghi rõ cảnh báo này.

Bộ trigger có cả cách phát âm của người Việt (`gia vit`, `da vit`…) vì model
vi-VN thường phiên âm "Jarvis" như vậy.

Muốn đổi sang Porcupine: chỉ cần implement lại interface `WakeWordEngine` và đổi
một dòng `@Binds` trong `VoiceModule`. Không phần nào khác của app phải sửa.

## 8. Kiểm thử

```bash
./gradlew testDebugUnitTest
```

**98 test, 0 fail.** Phủ phần logic thực sự test được trên JVM:

| Bộ test | Số test | Nội dung |
|---|---|---|
| `LocalIntentMatcherTest` | 18 | Khớp lệnh 2 ngôn ngữ, và **từ chối khớp** khi câu có nhiều bước |
| `AnthropicMapperTest` | 18 | `stop_reason: refusal`, replay `tool_use`, `input_schema`, fallbacks |
| `OllamaMapperTest` | 13 | Arguments dạng object (khác OpenAI), model không hỗ trợ tool, lỗi model chưa pull |
| `GeminiMapperTest` | 12 | functionCall → tool call, lỗi API, tool result → `functionResponse` |
| `OpenAiMapperTest` | 12 | `tool_calls`, arguments JSON hỏng, phân loại lỗi retry được |
| `ToolSchemaTest` | 10 | Sinh JSON Schema, trùng tên tool |
| `AppMatcherTest` | 8 | Xếp hạng tên app, bỏ dấu, xử lý nhập nhằng |
| `LanguageDetectorTest` | 7 | Nhận diện ngôn ngữ, kể cả tiếng Việt không dấu |

### Giới hạn kiểm thử — nói rõ ngay

**Chưa có instrumented test và chưa chạy thử trên thiết bị thật.** Máy build
không có điện thoại hay emulator kết nối, nên toàn bộ hành vi runtime — orb nổi
vẽ đúng chưa, gesture accessibility có ăn không, wake word nghe được không, TTS
tiếng Việt phát ra sao — **bạn cần test trên S24 Ultra rồi báo lại**. Mức đảm bảo
hiện tại là: compile sạch, và logic thuần được test tự động.

Bật **Debug Console** trong Settings để xem trace `VOICE → AI → TOOL → RESULT → TTS`
khi cần tìm nguyên nhân.

## 9. Bảo mật

- API key chỉ nằm trong `EncryptedSharedPreferences` (AES256-GCM, keystore-backed).
  Không hard-code, không ghi log, không hiện lại dạng plain text — kể cả cho chính
  người vừa nhập (ô nhập dùng `PasswordVisualTransformation`, chỉ hiện dạng mask).
- Gemini key gửi qua header `x-goog-api-key` chứ không phải query `?key=` — để
  secret không lọt vào URL, thứ dễ bị ghi log nhất.
- Backup đám mây và device transfer bị tắt hoàn toàn (`data_extraction_rules.xml`).
- Nội dung thông báo chỉ giữ trong RAM, không ghi vào database hay debug log.
- Không dùng `QUERY_ALL_PACKAGES`; danh sách app đọc qua `<queries>` launcher intent.

## 10. Tài liệu thiết kế

- Spec: [docs/superpowers/specs/2026-08-23-jarvis-android-assistant-design.md](docs/superpowers/specs/2026-08-23-jarvis-android-assistant-design.md)
- Kế hoạch triển khai: [docs/superpowers/plans/2026-08-23-jarvis-android-assistant.md](docs/superpowers/plans/2026-08-23-jarvis-android-assistant.md)
