# 안구마우스 (EyeMouse)

전면 카메라로 **시선(홍채)** 을 추적해서 폰 화면에 커서를 띄우고,
**한 곳을 응시**하거나 **눈을 길게 감으면** 그 위치를 터치해 주는 안드로이드 앱입니다.

- 얼굴/홍채 추적: MediaPipe Face Landmarker (기기 안에서만 처리, 영상 저장·전송 없음)
- 화면 조작: 접근성 서비스(커서 오버레이 + 터치/스와이프/뒤로/홈)
- 최소 Android 8.0 (API 26)

## APK 받는 법 (GitHub 자동 빌드)

1. GitHub에서 새 저장소(repository)를 만든다. (private 가능)
2. 이 폴더의 **모든 파일(숨김 폴더 `.github` 포함)** 을 저장소에 올린다.
   - 웹: 저장소 화면 → *Add file → Upload files* 에 폴더 내용을 끌어다 놓기
   - 또는 git:
     ```
     git init && git add . && git commit -m "init"
     git branch -M main
     git remote add origin https://github.com/<내아이디>/<저장소>.git
     git push -u origin main
     ```
3. 저장소의 **Actions** 탭에서 *Build APK* 가 초록색으로 끝날 때까지 기다린다 (약 5~10분).
4. 저장소 오른쪽 **Releases → 안구마우스 최신 APK** 에서 `EyeMouse.apk` 를 폰으로 내려받아 설치한다.
   (설치 시 "출처를 알 수 없는 앱" 허용 필요)

> 빌드가 빨간색(실패)이면 Actions 로그의 마지막 오류 메시지를 그대로 알려주세요.

## Android Studio로 직접 빌드할 때

1. `face_landmarker.task` 를 아래 주소에서 받아 `app/src/main/assets/` 에 넣는다.
   `https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/1/face_landmarker.task`
2. Android Studio로 이 폴더를 열고 *Build → Build APK(s)*.

## 처음 사용하기

1. 앱 실행 → **카메라 권한 허용**
2. **접근성 서비스 켜기** → 목록에서 *안구마우스* 켜기
   - 스위치가 회색이면: 앱 정보 → 오른쪽 위 ⋮ → **제한된 설정 허용** 후 다시 켜기 (Android 13+ 직접 설치 앱)
3. **시선 보정 시작** → 폰을 세워 두고 머리는 가만히, 눈만 움직여 나타나는 점 9개를 바라보기
4. **추적 시작** → 화면에 하늘색 커서가 나타남

## 조작

| 동작 | 방법 |
|---|---|
| 터치 | 한 곳을 약 1초 응시 (노란 링이 차오름) |
| 터치(대안) | 눈을 0.5초 이상 길게 감았다 뜨기 → 감기 직전 위치 터치 |
| 정지/재개 | 왼쪽 위 모서리 응시 |
| 최근 앱 | 오른쪽 위 모서리 응시 |
| 뒤로 / 홈 | 왼쪽 아래 / 오른쪽 아래 모서리 응시 |
| 스크롤 | 위/아래 가운데 가장자리 응시 |

## 정확도를 높이는 팁

- 밝은 곳에서, 폰을 거치대에 고정하고, 얼굴이 카메라 정면에 오게 합니다.
- 자세가 많이 바뀌면 **시선 보정을 다시** 하세요.
- 커서가 떨리면 *커서 부드러움*을 올리고, 반응이 느리면 내리세요.
- 안경 반사가 심하면 인식이 흔들릴 수 있어요.

## 구조

```
app/src/main/java/com/eyemouse/app/
  MainActivity.kt            설정/상태 화면
  CalibrationActivity.kt     9점 보정 화면
  EyeTrackingService.kt      카메라 + MediaPipe → 시선 특징값 (포그라운드 서비스)
  GazeEngine.kt              특징값 → 화면 좌표 (릿지 회귀 + 스무딩), 보정 저장
  EyeAccessibilityService.kt 커서 오버레이, 응시/눈감기 클릭, 모서리 동작
  CursorView.kt              커서·모서리 버튼 그리기
```
