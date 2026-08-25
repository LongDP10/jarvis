# Tự động cập nhật qua GitHub

Sau khi thiết lập xong một lần, quy trình của bạn là:

```
sửa code → git push → chờ ~4 phút → mở JARVIS → Settings → Kiểm tra cập nhật → Cài
```

Không còn xuất APK, không còn cắm cáp, không còn gửi file sang điện thoại.

---

## Bước 1 — Tạo khoá ký (chỉ làm một lần)

APK bắt buộc phải được ký, và **phải ký bằng đúng một khoá mãi mãi**. Android chỉ
cho phép cập nhật đè lên khi chữ ký khớp; đổi khoá thì phải gỡ app cài lại từ đầu.

Mở PowerShell tại thư mục dự án:

```powershell
& "C:\Program Files\Android\Android Studio1\jbr\bin\keytool.exe" -genkeypair -v -keystore jarvis-release.keystore -alias jarvis -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=JARVIS, O=Personal, C=VN"
```

Nó sẽ hỏi mật khẩu hai lần (keystore và key) — **đặt cùng một mật khẩu cho đơn giản**,
và ghi lại chỗ nào an toàn.

> **Tôi cố tình không tự tạo khoá này cho bạn.** Mật khẩu sẽ nằm trong lịch sử chat,
> và quan trọng hơn: mất file `jarvis-release.keystore` là mất khả năng cập nhật
> vĩnh viễn. Nó phải là thứ bạn sở hữu và tự sao lưu. File đã được `.gitignore`
> nên sẽ không bao giờ lọt lên GitHub.

Chuyển sang base64 để nhét vào GitHub Secret (lệnh này copy thẳng vào clipboard):

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("jarvis-release.keystore")) | Set-Clipboard
```

## Bước 2 — Tạo repo và đẩy code lên

Tạo một repository **public** rỗng trên github.com (đừng tick "Add a README"), rồi:

```bash
git remote add origin https://github.com/TEN_CUA_BAN/jarvis.git
```

```bash
git branch -M main && git push -u origin main
```

Repo để public là quyết định bạn đã chọn, và nó là lý do việc tự cập nhật chạy được
mà không cần token: file APK trong Release tải được ẩn danh. Trong mã nguồn **không
có API key nào** — key nằm trong `EncryptedSharedPreferences` trên máy bạn, và
`local.properties` đã bị `.gitignore`.

## Bước 3 — Thêm 4 secret

Trên GitHub: **Settings → Secrets and variables → Actions → New repository secret**.

| Tên secret | Giá trị |
|---|---|
| `KEYSTORE_BASE64` | Chuỗi base64 vừa copy ở Bước 1 |
| `KEYSTORE_PASSWORD` | Mật khẩu bạn vừa đặt |
| `KEY_ALIAS` | `jarvis` |
| `KEY_PASSWORD` | Mật khẩu bạn vừa đặt |

Thiếu `KEYSTORE_BASE64` thì CI dừng ngay với thông báo rõ ràng, thay vì tạo ra một
APK không ký mà bạn không cài được.

## Bước 4 — Trỏ app về repo của bạn

Sửa **một dòng** trong `app/src/main/res/values/strings.xml`:

```xml
<string name="github_repo" translatable="false">TEN_CUA_BAN/jarvis</string>
```

Rồi:

```bash
git add -A && git commit -m "Point the updater at my repository" && git push
```

## Bước 5 — Cài bản đầu tiên bằng tay (chỉ lần này)

Vào tab **Actions** trên GitHub xem build chạy. Xong thì sang tab **Releases**.

Trên điện thoại, mở đúng trang Release đó và tải file `jarvis-1.0.x.apk`. Android sẽ
hỏi có cho phép cài từ nguồn này không — đồng ý.

Từ lần sau trở đi: **Settings → Cập nhật → Kiểm tra cập nhật**.

---

## Ba điều cần biết trước

**1. Bản release là một app khác với bản debug bạn đang có.**
Bản debug có applicationId `com.jarvis.assistant.debug`, bản release là
`com.jarvis.assistant`. Chúng cài song song và không đè lên nhau. **Hãy gỡ bản debug
đi** để khỏi có hai JARVIS trên máy và khỏi nhầm lẫn khi debug.

**2. JARVIS cần quyền cài đặt ứng dụng.**
Lần đầu bấm "Tải và cài", nếu chưa có quyền, app sẽ nói rõ và đưa nút mở đúng màn
hình cài đặt. Android **không bao giờ** cho app tự cài ngầm — bạn luôn thấy hộp thoại
xác nhận của hệ thống. Đó là đúng, không phải hạn chế cần lách.

**3. Minification đang tắt.**
`isMinifyEnabled = false` cho bản release. Room, Hilt và kotlinx.serialization đều
dùng reflection; một bản bị ProGuard cắt sai sẽ lỗi trên máy bạn theo kiểu cực khó
chẩn đoán từ xa. APK to hơn, đổi lại bản release chạy giống hệt bản debug. Bật lại
khi app đã ổn định trên thiết bị.

## Cơ chế đánh số phiên bản

`versionCode` lấy từ `github.run_number`, và tag Release là `v1.0.<số đó>`. Updater
chỉ đọc phần số cuối của tag để so sánh — nên nó biết có bản mới hay không **mà không
cần tải gì cả**.

Nghĩa là số phiên bản chỉ tăng khi CI chạy. Build ở máy bạn luôn là `1.0-dev`
(versionCode 1).

## Khi hỏng thì xem đâu

| Triệu chứng | Chỗ cần xem |
|---|---|
| CI đỏ ở bước "Restore signing keystore" | Chưa thêm secret `KEYSTORE_BASE64` |
| CI đỏ ở bước "Build signed release APK" | Sai mật khẩu hoặc sai alias trong secret |
| App nói "Chưa cấu hình repository GitHub" | Chưa sửa `github_repo` ở Bước 4 |
| App nói "Repository đó chưa có bản phát hành nào" | CI chưa chạy xong, hoặc build hỏng |
| Cài đè báo lỗi chữ ký | Keystore đã đổi. Gỡ app rồi cài lại |
