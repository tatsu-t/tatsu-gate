# Gate

Java 21 向け軽量 HTTP フレームワーク。Jetty をベースに仮想スレッドをサポートします。

[English README](README.md)

## 目次

- [要件](#要件)
- [クイックスタート](#クイックスタート)
- [ルーティング](#ルーティング)
- [Context API](#context-api)
- [ミドルウェア](#ミドルウェア)
- [WebSocket](#websocket)
- [CORS](#cors)
- [エラーハンドリング](#エラーハンドリング)
- [共有ユーティリティ](#共有ユーティリティ)
- [モジュール](#モジュール)
- [データベース](#データベース)
- [設定](#設定)
- [サーバーライフサイクル](#サーバーライフサイクル)
- [ビルド](#ビルド)
- [ライセンス](#ライセンス)

## 要件

- Java 21
- Gradle 9.x

## クイックスタート

### 1. コントローラを作成する

```java
import dev.gate.annotation.GateController;
import dev.gate.core.Context;
import dev.gate.mapping.GetMapping;
import dev.gate.mapping.PostMapping;

@GateController
public class HelloController {

    @GetMapping("/hello")
    public void hello(Context ctx) {
        ctx.result("Hello, World!");
    }

    @GetMapping("/hello/{name}")
    public void helloName(Context ctx) {
        ctx.result("Hello, " + ctx.pathParam("name") + "!");
    }

    @PostMapping("/echo")
    public void echo(Context ctx) {
        ctx.json(ctx.bodyAs(MyRequest.class));
    }
}
```

### 2. サーバーを起動する

```java
import dev.gate.core.*;

public class Main {
    public static void main(String[] args) throws Exception {
        Config config = ConfigLoader.load();
        Database.init(config.getDatabase());

        Gate gate = new Gate();
        gate.register(new HelloController());

        GateServer server = gate.start(config.getPort());

        Runtime.getRuntime().addShutdownHook(new Thread(Database::close));
        server.join();
    }
}
```

## ルーティング

### アノテーション方式

```java
@GateController
public class UserController {

    @GetMapping("/users")
    public void list(Context ctx) { ... }

    @GetMapping("/users/{id}")
    public void get(Context ctx) { ... }

    @PostMapping("/users")
    public void create(Context ctx) { ... }

    @PutMapping("/users/{id}")
    public void update(Context ctx) { ... }

    @DeleteMapping("/users/{id}")
    public void delete(Context ctx) { ... }

    @PatchMapping("/users/{id}/status")
    public void patch(Context ctx) { ... }
}
```

インスタンスを手動で登録する場合：

```java
gate.register(new UserController());
```

パッケージをスキャンして `@GateController` クラスを自動登録する場合：

```java
gate.scan("com.example.controllers");
```

### プログラム方式（ラムダ）

```java
Gate gate = new Gate();

gate.get("/ping", ctx -> ctx.result("pong"));
gate.post("/data", ctx -> ctx.json(ctx.bodyAs(Data.class)));
```

### パスパラメータ

波括弧構文を使用します。パラメータは UTF-8 で URL デコードされて渡されます。

```java
@GetMapping("/users/{id}")
public void get(Context ctx) {
    String id = ctx.pathParam("id");
}
```

- 空のパラメータ名（`{}`）や同一パターン内の重複名は登録時に `IllegalArgumentException` が発生します。
- 完全一致ルートはパターンルートより常に優先されます。
- パターンルート内では、先に登録されたものが優先されます。
- 末尾スラッシュは一致前に除去されます（`/users/` は `/users` と同じ扱い）。

## Context API

### リクエスト

| メソッド | 戻り値 | 説明 |
|---|---|---|
| `ctx.path()` | `String` | 正規化済みリクエストパス |
| `ctx.method()` | `String` | HTTP メソッド文字列（例: `"GET"`） |
| `ctx.pathParam("name")` | `String` または `null` | パスパラメータの値 |
| `ctx.query("key")` | `String` または `null` | クエリ文字列パラメータ |
| `ctx.requestHeader("name")` | `String` または `null` | リクエストヘッダの値 |
| `ctx.body()` | `String` | リクエストボディ全文（初回読み込み後キャッシュ。上限 1 MB） |
| `ctx.bodyAs(MyClass.class)` | `T` または `null` | Jackson で JSON をオブジェクトに変換。ボディが空なら `null` を返す。 |

### レスポンス

すべてのレスポンスメソッドはメソッドチェーン可能（`this` を返す）。

| メソッド | 説明 |
|---|---|
| `ctx.result("text")` | テキストレスポンス（`text/plain; charset=utf-8`） |
| `ctx.json(object)` | JSON レスポンス（`application/json; charset=utf-8`） |
| `ctx.jsonRaw("{...}")` | シリアライズ済み JSON 文字列をそのままボディに設定 |
| `ctx.jsonBytes(bytes)` | シリアライズ済み UTF-8 JSON バイト列をゼロコピーで設定（キャッシュ層向け） |
| `ctx.status(201)` | HTTP ステータスコードを設定（デフォルト: `200`） |
| `ctx.header("X-Key", "val")` | レスポンスヘッダを設定。キーまたは値に `\r`/`\n` が含まれる場合は `IllegalArgumentException` |

```java
ctx.status(404).json(Map.of("error", "not found"));
```

### アトリビュート

リクエスト単位のアトリビュートで、ミドルウェアからルートハンドラへデータ（検証済みの認証情報など）を渡せます：

```java
ctx.setAttribute("verified_email", email);   // before フィルタ内
String email = ctx.getAttribute("verified_email");  // ハンドラ内
```

## ミドルウェア

```java
gate.before(ctx -> {
    String token = ctx.requestHeader("Authorization");
    if (token == null) {
        ctx.status(401).result("Unauthorized");
        throw new RuntimeException("unauthorized");
    }
});

gate.after(ctx -> {
    // ログ記録、メトリクス収集など
});
```

リクエストの実行順序：

```
before フィルタ（登録順）-> ルートハンドラ -> after フィルタ（登録順）-> レスポンス書き込み
```

注意: `before` フィルタで `ctx.status(401)` をセットしても、それだけではルーティングは止まりません。
ルートハンドラの実行を止めるには必ず例外を `throw` してください。
投げられた例外はエラーハンドラに渡されます。デフォルトの 500 レスポンスを避けたい場合は、
例外の種類を判定するカスタムエラーハンドラを登録してください。

`ctx.halt()` を呼び出すと、例外なしでルーティングを中断できます。ルートハンドラはスキップされ、
after フィルタに処理が移ります。ただし `halt()` を呼んだ時点で残りの before フィルタも実行されなくなります。
`halt()` より前に登録されたフィルタのみが実行済みになります。

after フィルタは before フィルタやルートハンドラが例外を投げた場合でも必ず実行されます。
各 after フィルタは個別の try/catch で囲まれているため、1 つが失敗しても残りは実行されます。
レスポンスはすべての after フィルタが完了した後に書き込まれるため、after フィルタ内でもレスポンスを変更できます。

## WebSocket

```java
@GateController
public class ChatController {

    @WsMapping("/chat")
    public void chat(WsContext ctx, String message) {
        if (ctx.isOpen()) {
            ctx.send("Echo: " + message);
        }
    }
}
```

`WsContext` API：

| メソッド | 説明 |
|---|---|
| `ctx.send(String)` | テキストフレームを送信。`IOException` 発生時は `UncheckedIOException` をスロー。 |
| `ctx.isOpen()` | 接続が開いている場合 `true` を返す。送信前に確認すること。 |

送信前に `ctx.isOpen()` を確認することを推奨します。リモート側によって接続が閉じられた場合、
Jetty が `IOException` 以外の例外をスローする可能性があり、その場合は `UncheckedIOException` に
ラップされずそのまま伝播します。

デフォルトの最大テキストメッセージサイズは 64 KB です。`start()` 呼び出し前に変更できます：

```java
gate.wsMaxMessageSize(128 * 1024);
```

## CORS

```java
gate.cors("https://example.com");  // 特定オリジン
gate.cors("*");                     // ワイルドカード（credentials 不可）
```

すべてのレスポンスに以下のヘッダが付与されます：

```
Access-Control-Allow-Origin: <値>
Access-Control-Allow-Methods: GET, POST, PUT, DELETE, PATCH, OPTIONS
Access-Control-Allow-Headers: Content-Type, Authorization, X-API-Key
Access-Control-Max-Age: 86400
```

オリジンが `"*"` でない場合のみ `Access-Control-Allow-Credentials: true` が追加されます。

プリフライト `OPTIONS` リクエストも before フィルタを通過してから（IP 許可リスト等のネットワークレベルのチェックを適用するため）、ルートハンドラを実行せずに `204` を返します。認証情報を要求するフィルタは自分で OPTIONS をスキップしてください — ブラウザはプリフライトに認証情報を付けません。

## エラーハンドリング

```java
gate.errorHandler((ctx, e) -> {
    ctx.status(500).json(Map.of("error", e.getMessage()));
});
```

デフォルトのハンドラは例外をログに記録し `500 Internal Server Error` を返します。
after フィルタ内でスローされた例外はログに記録されて握りつぶされ、エラーハンドラには届きません。

エラーハンドラ自身が例外をスローした場合、その例外はキャッチされません。after フィルタは
`finally` により実行されますが、レスポンス書き込みがスキップされ不完全なレスポンスが返る可能性があります。
エラーハンドラは例外が発生しないシンプルな実装にしてください。

### ClientErrorException

フィルタやハンドラから `ClientErrorException(status, message)` をスローすると、指定した 4xx ステータスと
`{"error": message}` の JSON ボディでリクエストを打ち切れます。エラーハンドラより**前に**フレームワークが
キャッチするため、クライアント起因のエラーがサーバーエラー処理（やそこに紐づくアラート）と混ざりません。
リクエストボディのサイズ超過（413）ではフレームワーク自身がこの例外を投げます。

```java
if (!valid(input)) throw new ClientErrorException(422, "invalid input");
```

## 共有ユーティリティ

| クラス | 説明 |
|---|---|
| `Json.MAPPER` | アプリ全体で共有する Jackson `ObjectMapper`。設定を変えたい場合は `Json.MAPPER.copy()` で派生させ、共有インスタンスは変更しないこと。 |
| `Http.CLIENT` | アプリ全体で共有する `java.net.http.HttpClient`（接続タイムアウト 5 秒）。読み取りタイムアウトは `HttpRequest.timeout(...)` でリクエスト単位に指定。 |

クラスごとに個別インスタンスを持つとシリアライザキャッシュやコネクションプールが重複するため、
アプリ内（および `gate-modules` 内）の全クラスで 1 インスタンスを共有します。

## モジュール

`gate-core` は超最低限（ルーティング・Context・ミドルウェア・WebSocket・設定・DB プール）に保ち、
それ以外はオプションの `gate-modules` に置いています。内容は実運用（Cloudflare + Cloud Run 構成で
トラフィックを捌いた学祭バックエンド）から抽出したミドルウェアとユーティリティです：

```kotlin
dependencies {
    implementation(project(":gate-core"))
    implementation(project(":gate-modules"))  // 任意
}
```

| パッケージ | クラス | 説明 |
|---|---|---|
| `modules.security` | `SecurityHeaders` | セキュリティ系レスポンスヘッダ（CSP・HSTS 等）を付与する after フィルタ（ビルダー式） |
| `modules.metrics` | `RequestMetrics` | ロックフリーのリクエスト数・p50/p95 レイテンシ・24h リングバッファ・上位エンドポイント |
| `modules.auth` | `ApiKeyAuth` | `X-API-Key` 認証（定数時間比較）。読み取り専用キーの段階も任意で設定可能 |
| `modules.cloudflare` | `CloudflareIpFilter` | Cloudflare 経由以外のリクエストを拒否（XFF/CIDR 検証、または `ORIGIN_SHARED_SECRET` ヘッダ） |
| `modules.cloudflare` | `CfAccessAuth` | 管理ルート向け Cloudflare Access JWT 検証（JWKS 取得 + RS256、JWT ライブラリ不使用） |
| `modules.cloudflare` | `CfPurge` | Cloudflare キャッシュ purge（全体 / URL 単位）。書き込み起点でエッジ鮮度をイベント駆動に |
| `modules.firebase` | `FirebaseAppCheckAuth` | クライアント書き込みエンドポイント向け Firebase App Check JWT 検証 |
| `modules.cache` | `TtlCache` | stale-on-error 付き TTL キャッシュ |
| `modules.cache` | `HttpCache` | ETag/gzip 事前計算エントリ + RFC 9110 条件付きレスポンス配信（`304`・`Vary`） |
| `modules.cache` | `BoundedLruCache` | サイズ上限付き LRU（TTL 任意）。Caffeine 不使用で native-image フレンドリー |
| `modules.idempotency` | `IdempotencyFilter` | `X-Request-Id` による重複書き込み拒否（400/409） |
| `modules.routes` | `YamlRouteLoader` | `routes.yaml` から読み取り専用 GET ルートを宣言的に登録（SQL は allowlist 検証、背景更新キャッシュ対応） |
| `modules.audit` | `AuditLog` | 管理操作を監査テーブルへ記録。失敗しても操作自体は壊さない |
| `modules.notify` | `DiscordWebhook` | エラー・管理操作の Discord 通知（キー単位のデバウンス付き） |
| `modules.logging` | `LogBuffer` | 直近 300 件のログをメモリに保持する Logback アペンダ（管理エンドポイント向け） |
| `modules.logging` | `CancelledKeyExceptionFilter` | 無害なクライアント切断ノイズを落とす Logback フィルタ |

典型的な組み込み（順序が重要 — ネットワークチェック → 認証 → メトリクス）：

```java
Gate gate = new Gate();
gate.before(new CloudflareIpFilter());
gate.before(ApiKeyAuth.builder().key(System.getenv("API_KEY")).exemptPath("/health").build());
gate.before(new CfAccessAuth());              // /admin を保護
gate.before(RequestMetrics.get()::startTimer);
gate.after(RequestMetrics.get()::record);
gate.after(SecurityHeaders.defaults()::handle);
```

モジュールの設定は環境変数で行います。詳細は各クラスの Javadoc を参照してください
（`API_KEY`、`CF_ACCESS_AUD`、`CF_ACCESS_TEAM_DOMAIN`、`ADMIN_EMAILS`、`CF_API_TOKEN`、
`CF_ZONE_ID`、`FIREBASE_PROJECT_ID`、`DISCORD_WEBHOOK_URL` など）。

## データベース

HikariCP を使った PostgreSQL サポートが組み込まれています。

```java
// 起動時に初期化（gate.start() より前に呼ぶこと）
Database.init(config.getDatabase());

// ハンドラ内で使用する
try (Connection conn = Database.getConnection();
     PreparedStatement ps = conn.prepareStatement("SELECT * FROM users WHERE id = ?")) {
    ps.setInt(1, id);
    ResultSet rs = ps.executeQuery();
    ...
}

// シャットダウン時に閉じる
Database.close();
```

### スキーマの初期化

`src/main/resources/schema.sql` を配置すると `Database.init()` 時に自動実行されます。
再起動しても問題ないよう `IF NOT EXISTS` を使ってべき等に書いてください。

```sql
CREATE TABLE IF NOT EXISTS users (
    id         SERIAL PRIMARY KEY,
    name       VARCHAR(255) NOT NULL,
    email      VARCHAR(255) NOT NULL UNIQUE,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
```

`schema.sql` が見つからない場合は警告がログに出力され、起動は続行します。

### Cloud SQL（GCP）

```yaml
database:
  cloudSqlInstance: "my-project:us-central1:my-instance"
  name: mydb
  user: myuser
  password: ""
```

`cloudSqlInstance` が空でない場合、Cloud SQL Socket Factory 経由で接続されます。`host`/`port` は無視されます。
環境変数 `CLOUD_SQL_INSTANCE` でも設定できます。

## 設定

`src/main/resources/config.yml`：

```yaml
port: 8080
env: development
name: MyApp
database:
  host: localhost
  port: 5432
  name: mydb
  user: postgres
  password: ""
  cloudSqlInstance: ""   # GCP Cloud SQL インスタンス（project:region:instance）
  maxPoolSize: 10
```

`config.yml` が存在しない、またはパースに失敗した場合はデフォルト値が使用され、警告がログに出力されます。

データベース関連のフィールドはすべて環境変数でオーバーライドできます：

| 環境変数 | 対応する設定フィールド |
|---|---|
| `DB_HOST` | `database.host` |
| `DB_PORT` | `database.port` |
| `DB_NAME` | `database.name` |
| `DB_USER` | `database.user` |
| `DB_PASSWORD` | `database.password` |
| `CLOUD_SQL_INSTANCE` | `database.cloudSqlInstance` |

## サーバーライフサイクル

`gate.start(port)` は `GateServer` インスタンスを返します。

| メソッド | 説明 |
|---|---|
| `server.join()` | サーバーが停止するまでブロックします。`main` の末尾で呼び出してください。 |
| `server.stop()` | サーバーをグレースフルに停止します。 |
| `server.isRunning()` | サーバーが稼働中の場合 `true` を返します。 |

サーバーを停止する JVM シャットダウンフックは自動的に登録されます。
`Database.close()` など他のクリーンアップ処理は別途フックを登録してください。

## ビルド

```bash
./gradlew build
```

## ライセンス

MIT License

Copyright (c) 2026 tatsu-t

以下に定める条件に従い、本ソフトウェアおよび関連文書のファイル（以下「ソフトウェア」）の複製を取得するすべての人に対し、ソフトウェアを無制限に扱うことを無償で許可します。これには、ソフトウェアの複製を使用、複写、変更、結合、掲載、頒布、サブライセンス、および/または販売する権利、およびソフトウェアを提供する相手に同じことを許可する権利も無制限に含まれます。

上記の著作権表示および本許諾表示を、ソフトウェアのすべての複製または重要な部分に記載するものとします。

ソフトウェアは「現状のまま」で、明示であるか暗黙であるかを問わず、何らの保証もなく提供されます。ここでいう保証とは、商品性、特定の目的への適合性、および権利非侵害についての保証も含みますが、それに限定されるものではありません。作者または著作権者は、契約行為、不法行為、またはそれ以外であろうと、ソフトウェアに起因または関連し、あるいはソフトウェアの使用またはその他の扱いによって生じる一切の請求、損害、その他の義務について何らの責任も負わないものとします。
