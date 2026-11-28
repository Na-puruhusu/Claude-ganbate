# Zombie Apocalypse (Claude-ganbate)

Minecraft 1.20.1 / Forge 47.4.28 mod (Java 17)。**日数が経つほどゾンビが強く・多くなる**ゾンビアポカリプス。

- 難易度は世界の日数で上がる(Lv10は80日目)。装備が弱ければ緩み、強ければ少し厳しくなる
- 特殊ゾンビ5種、7日ごとのホードナイト、ホード生還報酬
- ゾンビ肉256個(4スタック)→ 専用の台**腐肉の祭壇**で**永続エリクサー**1個(死んでも消えない)
  - ダメージ(+15/30/45/60%)・スピード(+25/50/75/100%)・耐久(被ダメージ-20/40/60%)・体力(ハート+2/4/6/8/10)
  - 祭壇のレシピ: 腐肉×3 / 骨×2 / 大釜 / 石レンガ×3(`FFF/BCB/SSS`)。右クリックで作成画面
- `/zapoc info` `/zapoc potions` など

設計の詳細・数値の根拠: [docs/DIFFICULTY.md](docs/DIFFICULTY.md)

## 開発
- ビルド: `./gradlew build`(push時にGitHub Actionsでも自動実行)。成果物はActionsのartifact `mod-jar`
- 難易度の設計検証(Minecraft不要): `./tools/run-sim.sh`(`--report` で違反があっても全表を表示)
- アセット生成: `python3 tools/gen_assets.py`(Javaの `ElixirModel.java` の数値から祭壇のレシピ・モデル・テクスチャ・言語を生成。`--check` で最新か検証)
- CI: ビルド+設計検証+アセット鮮度チェック / 専用サーバーを起動してゲーム内の自己診断を実行(`ZAPOC_SELFTEST`)
