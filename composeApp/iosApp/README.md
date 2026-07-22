# iOS host (Xcode)

Compose Multiplatform の `ComposeApp.framework` を載せる Xcode プロジェクト用メモ。

## 前提

- macOS + Xcode 15+
- JDK 17
- リポジトリルートで:

```bash
./gradlew :composeApp:linkDebugFrameworkIosSimulatorArm64
```

## 最小ホスト

1. Xcode で iOS App（SwiftUI ライフサイクルでも UIKit でも可）を新規作成
2. Framework Search Paths に  
   `composeApp/build/bin/iosSimulatorArm64/debugFramework`（実機は `iosArm64`）を追加
3. `ComposeApp.framework` を Embed & Sign
4. `App` の入口:

```swift
import SwiftUI
import ComposeApp

@main
struct RelayIOSApp: App {
    var body: some Scene {
        WindowGroup {
            ComposeView().ignoresSafeArea(.all)
        }
    }
}

struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController()
    }
    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
```

Kotlin 側の UI・ドメインは共有モジュール（`RelaySharedApp` / `shared`）を使います。SwiftUI でアプリ本体を書き直していません。

この共有 UI は **開発プレビュー（機能制限あり）** です。Android 版の救助依頼（SOS）・Nearby 自動中継・Foreground Service は含まれません。安否・物資・地域情報の共有と地図確認、および PC Gateway の公開同期（開発用）のみに対応します。
