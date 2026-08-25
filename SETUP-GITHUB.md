# Tự động cập nhật qua GitHub

> **Thiết lập đã hoàn tất.** Repo, CI, khoá ký và cả 4 secret đều đã dựng xong và
> chạy xanh. Tài liệu này giữ lại để tra cứu khi cần dựng lại hoặc khi hỏng.

Quy trình hằng ngày của bạn:

```
sửa code → git push → chờ ~4 phút → JARVIS → Settings → Kiểm tra cập nhật → Cài
```

## Trạng thái hiện tại

| Hạng mục | Giá trị |
|---|---|
| Repo | https://github.com/LongDP10/jarvis (public) |
| CI | `.github/workflows/build.yml`, chạy mỗi lần push lên `main` |
| Khoá ký | `jarvis-release.keystore`, alias `jarvis`, hạn 10.000 ngày |
| Secret | `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` |
| Release | https://github.com/LongDP10/jarvis/releases |

**Sao lưu `jarvis-release.keystore` ra chỗ khác (Drive, USB).** Mất nó là mọi bản
cập nhật sau này không cài đè được — phải gỡ app, mất sạch cài đặt và lịch sử.
File này đã bị `.gitignore` nên không bao giờ lên repo.

---

## Dựng lại từ đầu (nếu đổi máy hoặc mất khoá)

**1. Tạo khoá ký**

```powershell
& "C:\Program Files\Android\Android Studio1\jbrin\keytool.exe" -genkeypair -v -keystore jarvis-release.keystore -alias jarvis -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=JARVIS, O=Personal, C=VN"
```

**2. Nạp secret bằng `gh`**

```powershell
$b64 = [Convert]::ToBase64String([IO.File]::ReadAllBytes("jarvis-release.keystore"))
gh secret set KEYSTORE_BASE64 --repo LongDP10/jarvis --body $b64
gh secret set KEYSTORE_PASSWORD --repo LongDP10/jarvis --body "MAT_KHAU"
gh secret set KEY_ALIAS --repo LongDP10/jarvis --body "jarvis"
gh secret set KEY_PASSWORD --repo LongDP10/jarvis --body "MAT_KHAU"
```

> **Bắt buộc dùng `--body`, không được pipe.** PowerShell 5.1 chèn CRLF khi pipe
> chuỗi dài sang lệnh native, và `base64 -d` trên runner Linux coi ký tự `` là
> input không hợp lệ. Đây chính là lỗi đã gặp lần đầu.

**3. Trỏ app về repo** — sửa `github_repo` trong `app/src/main/res/values/strings.xml`.

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
