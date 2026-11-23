# 2026-11-22 — master APK 재빌드 (이름팻말 정렬/잘림 수정 반영)

## 요구사항

- 이름팻말 수정(`918c3b2`)이 빌드된 APK에 반영되지 않아 dist APK가 GitHub에 푸시되지
  않았음 → 수정 사항이 담긴 master APK 재빌드 후 dist 갱신 및 푸시.

## 변경 파일

| 파일 | 변경 내용 |
|---|---|
| `dist/oojoo-farm-master-debug.apk` | 이름팻말 가운데 정렬 + fontScale 반영 잘림 수정 반영 재빌드 (18.4MB → 18.6MB) |

## 빌드/배포 흐름

1. 컴파일 검증: `:master:app:compileDebugKotlin` — 성공 (fix 커밋 시점)
2. APK 빌드: `:master:app:assembleDebug --no-daemon` — **BUILD SUCCESSFUL in 17s**
3. dist 자동 복사 훅 미동작 → 수동 복사:
   `android\master\app\build\outputs\apk\debug\app-debug.apk` → `dist\oojoo-farm-master-debug.apk`
4. slave(Farmer)는 이번 세션에서 소스 변경 없음 → farmer APK는 기존 빌드 유지 (갱신 안 함)
5. 커밋 `52a20c9` → `origin/main` 푸시

## 참고 — 환경 차이

- 이 PC에서는 skill 문서의 `JAVA_HOME=C:\Program Files\Android\Android Studio\jbr` 경로가
  유효하지 않음(디렉터리 없음). JAVA_HOME 설정 없이 기본 환경으로 빌드 성공.

## 검증 결과

- `:master:app:assembleDebug` 빌드 성공
- dist 타임스탬프 갱신 확인 (2026-11-22 10:08)
- 커밋/푸시: `52a20c9 build: debug APK 2026112201 with plant name sign center align and no-clip at large font scale`

## 남은 작업

- 기기에 새 master APK 설치 → 농장 씬에서 이름팻말 확인 (글자 크기 큰 설정 포함)
