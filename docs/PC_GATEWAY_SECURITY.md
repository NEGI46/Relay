# PC Gateway セキュリティ

実装済み:

- 未知Bridgeは期限付きペアリングコードとPC管理者承認が必要。自動登録しない。
- 承認後はランダムBearer tokenを発行し、SQLiteにはSHA-256 hashだけを保存する。
- Android tokenはAndroid Keystore AES/GCMで暗号化保存する。
- 同期要求ごとにBridge IDとBearer tokenを検証する。
- `messageId`と`(messageId, receiptType, actorId)`を一意制約で重複排除する。
- Payload、message type、status、priority、TTL、累積age、hop数、識別子長をPC側でも検証する。
- DB保存成功後だけGateway Receiptを生成する。

残存リスク:

- MVPのLAN HTTPはTLSなしで、信頼できない無線LANではBearer tokenを盗聴され得る。
- 管理者キーは環境変数で渡し、リポジトリやログへ保存しない。
- PKI、署名、tokenローテーション、監査ログ、複数Gateway信頼モデルは未実装である。
- `0.0.0.0`公開とWindows Firewall設定は運用者の責任であり、自動変更しない。
