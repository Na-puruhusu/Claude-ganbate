# Zombie Apocalypse (Claude-ganbate)

Minecraft 1.20.1 / Forge 47.4.28 mod (Java 17)。**日数が経つほどゾンビが強く・多くなる**ゾンビアポカリプス。

- 難易度は世界の日数で上がる(Lv10は80日目)。装備が弱ければ緩み、強ければ少し厳しくなる
- 特殊ゾンビ5種、7日ごとのホードナイト、ホード生還報酬
- ゾンビ肉 → 腐肉のエッセンス → **永続エリクサー**(活力・凶暴・俊足・頑強 × Lv1〜5。死んでも消えない)
- `/zapoc info` `/zapoc potions` など

設計の詳細・数値の根拠: [docs/DIFFICULTY.md](docs/DIFFICULTY.md)

## 開発
- ビルド: `./gradlew build`(push時にGitHub Actionsでも自動実行)。成果物はActionsのartifact `mod-jar`
- 難易度の設計検証(Minecraft不要): `./tools/run-sim.sh`(`--report` で違反があっても全表を表示)
- アセット生成: `python3 tools/gen_assets.py`(Javaの `ElixirModel.java` の数値からレシピ・モデル・テクスチャ・言語を生成。`--check` で最新か検証)
- CI: ビルド+設計検証+アセット鮮度チェック / 専用サーバーを起動してゲーム内の自己診断を実行(`ZAPOC_SELFTEST`)
