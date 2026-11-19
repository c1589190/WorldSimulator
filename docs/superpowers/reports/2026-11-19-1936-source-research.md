# 1936 剧本史料查证记录

> **状态**：调研输入
> **日期**：2026-11-19
> **本文性质**：检索路径、校核证据与复现记录
> **设计入口**：[1936 欧洲剧本与 Agent 推演](../specs/2026-11-19-1936-europe-agent-turns.md)

## 记录范围

每次查证记录要回答的问题、历史日期、使用的检索词或工具参数、来源 URL 与版本、原始记录位置、取值与换算方法、匹配到的稳定地址、证据冲突及处理结果。批量导入时再补预览、命令回执和保存节点。资料访问入口和查到的字段实行同一套记录口径，便于后续复用。

## 2026-11-19 首轮入口

| 要找的内容 | 实际检索及来源 | 当前结果 |
|---|---|---|
| 历史国家边界和首都 | 检索 `ETH Zurich CSS CShapes 2.0 historical country boundaries 1886 2019 dataset 1936`。检查 ETH 数据页的覆盖、下载格式和 License 段。[CShapes](https://icr.ethz.ch/data/cshapes/) | 用 2.0 GeoJSON 核对 1936-01-01 的德波陆省，单独保留但泽参考边界。授权为 CC BY-NC-SA 4.0。 |
| 多语地名与坐标 | 检索 `Natural Earth 1:10m populated places latitude longitude public domain official`。取固定 5.1.2 的 Populated Places GeoJSON。[数据说明](https://www.naturalearthdata.com/downloads/10m-cultural-vectors/10m-populated-places/) · [使用条款](https://www.naturalearthdata.com/about/terms-of-use/) | 读取了 Warsaw、Berlin、Paris、Rome、Moscow 的城市点及多语名。 |
| 同时期的产业与人口统计 | 检索 `League of Nations Statistical Yearbook 1936 1937 international statistics digitized UN Geneva 1936 industry population`。由联合国历史年鉴页面定位国联历史年鉴入口。[历史统计年鉴](https://unstats.un.org/unsd/publications/statistical-yearbook/past-issues/) | 已找到资料入口。具体年度、表页、统计口径和数值尚待查证。 |
| 地图源的开局文件 | 在固定提交 `d7a57759c188a034c49a0e96aceb1702fa76fc74` 枚举 `common/bookmarks` 和 `history/units`，并检查已使用的州史。 | 找到 `common/bookmarks/the_gathering_storm.txt` 及 GER、POL、ENG、FRA、ITA、SOV 的 1936 单位文件，部分有多个变体。文件用于找线索并关联当前地图编号。 |

源文件枚举可重跑。

```sh
git -C runtime/imports/MagnaEuropa rev-parse HEAD
git -C runtime/imports/MagnaEuropa ls-tree -r --name-only HEAD common/bookmarks
git -C runtime/imports/MagnaEuropa ls-tree -r --name-only HEAD history/units |
  rg '/(GER|POL|FRA|ENG|ITA|SOV).*1936'
```

## 地名和经纬度匹配预检

获取 Natural Earth 原始输入。

```sh
curl -LfsS 'https://raw.githubusercontent.com/nvkelso/natural-earth-vector/v5.1.2/geojson/ne_10m_populated_places.geojson' \
  -o /tmp/worldsim-1936-natural-earth-places.geojson
sha256sum /tmp/worldsim-1936-natural-earth-places.geojson
```

文件 SHA-256 为 `9b8e3de09048ef00dfc70357dbb9fa324493f214b5e0ae4daf1aa79a8d10116b`。读取 `features`，属性名转小写，再按 `name` 和 `adm0name` 选城市。Warsaw 对应 Poland，其 `name_pl` 为 Warszawa，德语别名为 Warschau，经纬度按经度在前为 `[21.005347, 52.230872]`。该结果用于地名候选与校准点检查。

预检使用 `world/exports/bb64c7db-1d4c-4cd2-a408-798552c4a172/` 的副本。在副本工作目录运行 `java -Xmx5g -jar ~/WorldSimulator/target/worldsim-country-orbat.jar mcp`。按 MCP stdio 初始化后调用 `tools/list`，枚举到 14 项工具。逐个运行如下参数的 `world_search`，将 `query` 替换为表中检索词。

```json
{"node":"main-0","kind":"province","query":"Warszawa","limit":4}
```

| 词 | 实际匹配 |
|---|---|
| Berlin | `main-0/map/province/6184` |
| Warszawa | `main-0/map/province/11800` |
| Warsaw | 0 条 |
| Paris | `main-0/map/province/19865` |
| London | London `12038` 和 Londonderry `19389` |
| Rome | 0 条 |
| Moscow | 0 条 |
| Lisboa | `main-0/map/province/19738` |

随后用同节点查看器的 `/api/search` 复核 Moskva，命中 `main-0/map/province/18646`。Roma 返回 Roman、Romans-sur-Isène 和 Kostroma 三个包含式匹配。检索结果需结合图幅坐标、上下文和源图编号校对，同名与近似匹配保留候选。MCP 退出码 0，所有结果的节点 hash 为 `sha256:097b73086f835cd63df788a787c09cd32fd165be896b39b158c85c43139371ff`，副本清单逐字节一致。原始记录为 `/tmp/worldsim-1936-mcp-preflight.json`。主图坐标模型与分图幅问题见[地理调查](2026-11-19-geography-country-orbat-recon.md)。

## 主图配准与留出检验

从 `main-0` 的程序可读视图提取省 ID、胜利点地名和省内显示点。用 Natural Earth `NAME` 和 `ADM0NAME` 区分同名城市，保留 `NAME_*` 别名。将 42 个点的 WGS84 坐标投影至 Miller 平面，用最小二乘拟合至 4096×3072 逻辑坐标。另 15 个点只参与检验与覆盖范围的确定。将预测点落到固定提交的 `map/provinces.bmp`，用 `map/definition.csv` 将像素颜色还原为省 ID。57 个校对地点的凸包定义坐标覆盖，海外插图及凸包以外的区域仍需各自校准。[配准脚本](../../../tools/prepare-1936-pilot.py)

15 个留出点有 12 个的名义像素与地图地名匹配。三个偏离点为 London `12038 → 12156`、Vienna `20848 → 20850`、Istanbul `15010 → 17867`。最大显示点残差为 4.749 个地图坐标单位，向上取整为查询半径 5。该数为本次留出点的观测误差。经纬度搜索会按所查节点的实际 polygon 检查此范围并返回候选，按完整省地址反查则用省内显示点估算经纬度。完整坐标、距离、预期省和像素命中见本地 `runtime/scenario-1936/prepared/research.json`。

## 历史边界与领土筛选

从 ETH 下载固定的 CShapes 2.0 GeoJSON，文件 SHA-256 为 `384b1ea90b9419f30a858d7ec237c85a22c60d1b35b5f85f215a1204f9989d42`。以 Gleditsch–Ward 国家代码 `gwcode` 区分实体，比较 `gwsyear/gwsmonth/gwsday` 和 `gweyear/gwemonth/gweday` 的闭区间是否包括 1936-01-01。读取 `capname`、`caplong` 和 `caplat`，以源地图对应地名确认首都省。[数据版本、作者及许可](https://icr.ethz.ch/data/cshapes/) · [GeoJSON](https://icr.ethz.ch/data/cshapes/CShapes-2.0.geojson)

| GeoJSON 记录 | 该行起止日期 | 首都与省 ID |
|---|---|---|
| 255 `Germany (Prussia)` | 1920-02-10 至 1938-09-29 | Berlin `6184` |
| 290 `Poland` | 1921-03-18 至 1945-05-07 | Warsaw `11800` |
| 291 `Danzig` | 1919-06-28 至 1938-08-31 | Danzig `10923`，本次显示独立史料边界 |

从 Magna 固定版本 `history/states/*.txt` 读 `history.owner` 和截至开局日的日期覆盖。与程序视图按州 ID 逐一核对省成员，成员不一致的州整体列入复核。本次没有此类州。将国家边界投影到主图，候选陆省的显示点落在对应国家且离边界超过 5 个地图坐标单位时，采用州史作为省级归属并记为 `inferred`。排除但泽范围，边界带和来源冲突逐省记入报告。归属记录包含史料日期、精确州史文件和历史边界来源，置信度保留为未知。实际控制待补证据。[固定版本州史](https://github.com/AzurCrystal/Magna-Europa/tree/d7a57759c188a034c49a0e96aceb1702fa76fc74/history/states)

| 国家 | 州史陆省 | 导入归属 | 距边界至多 5 | 来源边界冲突 |
|---|---:|---:|---:|---:|
| 德国 | 1,428 | 1,292 | 129 | 7 |
| 波兰 | 907 | 856 | 50 | 1 |

187 个省列入待复核。8 条来源冲突涉及 Kolomyja、Cuxhaven、Elbing、Fehmarn 及东弗里西亚和北弗里西亚群岛所在州，具体州 ID 和省 ID 留在 `research.json`。源库概化、省级图形和历史归属均需逐个核对。参考边界在查看器用虚线显示，实际归属底色由保存节点的 `setTerritory` 命令生成。

## 指挥部与编制

检索 `Georg Tessin Formationsgeschichte 1933 1939 Höhere Stäbe Bundesarchiv Panzerdivision`。从德国联邦档案馆军事资料入口下载 Georg Tessin 的《Formationsgeschichte der Wehrmacht 1933–1939》节选。扫描 PDF 的 `pypdf` 文本提取为空，改用 `PyMuPDF` 渲染和人工逐页阅读。PDF 第 5 页（印刷页 22）记录 1935-10-15 公开番号后第一至第三装甲师的驻地。PDF 第 8 页（印刷页 25）在 1935-10-01 改编表中列各师下辖同番号的装甲旅和步兵旅。以 1935 年 10 月记录推断开局时师部驻地和所列直属旅，数量留空。[档案馆资料入口](https://www.bundesarchiv.de/im-archiv-recherchieren/archivgut-recherchieren/nach-themen/hinweise-und-hilfsmittel-zu-den-preussisch-deutschen-streitkraeften-1849-1949/) · [驻地页](https://www.bundesarchiv.de/assets/bundesarchiv/de/Downloads/PDFs_Milhist-Fachliteratur-Auszuege/Tessin_Formationsgeschichte_1933-39_-_Hoehere_Staebe.pdf#page=5) · [直属旅页](https://www.bundesarchiv.de/assets/bundesarchiv/de/Downloads/PDFs_Milhist-Fachliteratur-Auszuege/Tessin_Formationsgeschichte_1933-39_-_Hoehere_Staebe.pdf#page=8)

检索 `Banaszek Udział wojska Kielc 1918 1939 2 Dywizji Piechoty Legionów BazHum`。基尔采博物馆站下载遇到 TLS 错误，转用波兰历史博物馆 BazHum 馆藏。用 `pypdf` 读 Tadeusz Banaszek 的 2012 年论文 PDF 第 2 页（印刷页 95），按 `garnizonu Kielce`、`2. Dywizji Piechoty Legionów` 定位。该段明确给出 1922 至 1939 年的第 2 军团步兵师师部、第 4 军团步兵团和第 2 军团轻炮兵团。用此日期范围定位 1936 年样本。[论文原页](https://bazhum.muzhp.pl/media/texts/studia-muzealno-historyczne/2012-tom-4/studia_muzealno_historyczne-r2012-t4-s95-107.pdf#page=2)

| 顶层单位 | 节点省地址 | 编制图抽录范围 |
|---|---|---|
| 德军第 1 装甲师师部 | `de-pl-1936-1/map/province/12392`（Weimar） | 第 1 装甲旅、第 1 步兵旅 |
| 德军第 2 装甲师师部 | `de-pl-1936-1/map/province/12730`（Würzburg） | 第 2 装甲旅、第 2 步兵旅 |
| 德军第 3 装甲师师部 | `de-pl-1936-1/map/province/6184`（Berlin） | 第 3 装甲旅、第 3 步兵旅 |
| 波军第 2 军团步兵师师部 | `de-pl-1936-1/map/province/19912`（Kielce） | 第 4 军团步兵团、第 2 军团轻炮兵团 |

师部生成兵牌，下属只进实际编制树。表中驻地指师部，整师覆盖范围、缺少的下属、人员、装备和理想编制保持未知。Magna 的 1936 部署文件和同系列论文可供下一批查证。[1936 单位文件](https://github.com/AzurCrystal/Magna-Europa/tree/d7a57759c188a034c49a0e96aceb1702fa76fc74/history/units) · [第 2 军团步兵师沿革](https://bazhum.muzhp.pl/media/files/Przeglad_Historyczno_Wojskowy/Przeglad_Historyczno_Wojskowy-r2011-t12_%2863%29-n4_%28237%29/Przeglad_Historyczno_Wojskowy-r2011-t12_%2863%29-n4_%28237%29-s29-54/Przeglad_Historyczno_Wojskowy-r2011-t12_%2863%29-n4_%28237%29-s29-54.pdf)

德国联邦档案馆 PDF 的 SHA-256 为 `eaca1a7d158f3e8bbc4d547b431a1d1acf7a559a8c5893e8660924ce397d2c96`，BazHum PDF 为 `4ae93891eff1fbceb4e6760048866d92ad4cd72f4d58c6d63df5e11124a143f9`。核对环境使用 `pypdf` 6.1.3 和 `PyMuPDF` 1.26.5，取对应 PDF 的零起编号 `4`、`7` 和 `1` 检查原页。

## 复现导入

从仓库根目录执行。准备 `main-0` 的可读视图及本地导出。缺少视图时通过 `world_view` 对相同基准生成。试点基准导出为 `world/exports/bb64c7db-1d4c-4cd2-a408-798552c4a172/`，新运行目录使用该导出的副本。已验收的 Python 依赖为 NumPy 2.5.5、Pillow 12.3.0 和 Shapely 2.1.2。

```sh
mkdir -p runtime/scenario-1936/sources
curl -LfsS 'https://raw.githubusercontent.com/nvkelso/natural-earth-vector/v5.1.2/geojson/ne_10m_populated_places.geojson' \
  -o runtime/scenario-1936/sources/ne_10m_populated_places-v5.1.2.geojson
curl -LfsS https://icr.ethz.ch/data/cshapes/CShapes-2.0.geojson \
  -o runtime/scenario-1936/sources/CShapes-2.0.geojson
PYTHONPATH=runtime/scenario-1936/python python3 tools/prepare-1936-pilot.py \
  --world world --magna runtime/imports/MagnaEuropa \
  --places runtime/scenario-1936/sources/ne_10m_populated_places-v5.1.2.geojson \
  --boundaries runtime/scenario-1936/sources/CShapes-2.0.geojson \
  --out runtime/scenario-1936/prepared

mkdir -p runtime/scenario-1936/fresh-case
cp -a world/exports/bb64c7db-1d4c-4cd2-a408-798552c4a172 runtime/scenario-1936/fresh-case/world
cp runtime/scenario-1936/prepared/geo-reference.json runtime/scenario-1936/fresh-case/world/
python3 tools/submit-scenario.py --jar target/worldsim-0.1.0-SNAPSHOT.jar \
  --case runtime/scenario-1936/fresh-case --plan runtime/scenario-1936/prepared/scenario.json \
  --report runtime/scenario-1936/fresh-case/receipt.json --apply
```

`geo-reference.json` 是与基准 hash 关联的只读查址参考。`scenario.json` 是 450 条 GM 命令的提交清单，`research.json` 保留校准点、日期、边界和省级待复核项。导入器先检查四个地名对应，再打开草稿，对每批命令执行预览和应用，最后调用一次 `edit_save`。省略 `--apply` 可查看回执并放弃草稿。命令通过 GM stdio MCP 执行。[准备脚本](../../../tools/prepare-1936-pilot.py) · [导入脚本](../../../tools/submit-scenario.py)

## 程序验收记录

早期运行显示 Istanbul 的留出点超出仅按拟合点圈出的范围。扩大范围前已核对留出距离和像素命中，最终将 57 个已核对地点共同纳入凸包，变换和 450 条命令保持逐字节一致。最终参考 hash 为 `sha256:2b32d5493fe5255bdcc31f6b727febf89d55f4eb89aa41372e29aa0f885c4a86`。

用最终地理参考在本地副本 `runtime/scenario-1936/demo/world/` 重跑。`world_geo_lookup` 复核 Berlin、Würzburg、Warsaw 和 Kielce。五批 `edit_apply` 分别校验 100、100、100、100、50 条命令，五批原有归属冲突数均为 0。`edit_save` 保存 `de-pl-1936-1`，hash `sha256:b756cfac845bb4532bb9ce70555cc1e0c99b9963faca250e1b8a1c24fce94c31`。回执在 `runtime/scenario-1936/import-receipt.json`。原世界 `world/world.json` 的 SHA-256 前后一致。

重启 GM MCP 后复查 4 个师部、8 个编制子节点和但泽归属未知。坐标 `27.564681,53.901923` 唯一命中明斯克 `11069`，柏林 `13.399603,52.523764` 和伊斯坦布尔 `28.974277,41.017602` 返回候选并包含对应地图地名省。输入 `-71.0589,42.3601` 返回覆盖外。用 `de-pl-1936-1/map/province/11800` 反查得到省内显示点约 `20.991775,52.217561`。`timeline_verify` 验证 2 个时间线节点，`world_export` 的地理参考文件与输入逐字节一致。重启留档 `runtime/scenario-1936/verify-mcp.json`。

在另一创世副本运行 3 条命令的默认预览，草稿丢弃后副本清单与创世导出逐字节相同，见 `runtime/scenario-1936/dry-run/receipt.json`。随后在该副本执行完整导入，逐批比对预览和应用的 hash。最终保存 hash 与两次导入的结果一致，见 `runtime/scenario-1936/import-parity.json`。

Windows 侧通过 `curl.exe -4 --noproxy '*'` 访问 8767 并取回该节点。Chromium 已验证史料边界、反查链接、波兰归属、Kielce 编制图和切回 `main-0`，见 `runtime/scenario-1936/verify-viewer.log`。

## 德波开局复核与补录

### 截图和边界证据

在 Chromium 打开 `de-pl-1936-1`，勾选国家归属，按相同倍率截取德波和波罗的海沿岸。再单独叠加 CShapes 边界，只看归属着色时也能定位原先沿边的浅色断带。对照波兰教育资料的历史地图，在 PDF 第 16 页查看德国本土、东普鲁士、波兰走廊及但泽自由市的相对位置，紫线标 1922 年议定边界。1936 年断面以 CShapes 和 Magna 州史核对。[历史地图第 16 页](https://static.zpe.gov.pl/portal/f/res/R1bZWIVmU6LrC/1617281984/15nPPFg0gUAHZtI3ZLsuVncIBPFcLfFe.pdf#page=16)

从 `world_view` 读同日州史涉及的省，从保存节点读实际归属。179 个省位于配准误差带内，按州史回填 `inferred`，继续保留 CShapes 作参考。另 8 省逐个反查省内点、核对相邻省和原州。六处沿岸省通过德国岛屿史料及 Cuxhaven、Ostfriesische Inseln、Fehmarn、Nordfriesische Inseln 的源州确认。`19934` 核对 Pröbbernau 一带 1920 年回归 Elbing 的边界变更。`13540` 在波罗边缘，对照 Czeremosz 的河界和州 1143，以原州所属近似离散省形归属。它的显示点距 CShapes 边界 5.26 地图单位，复核记录保留这一差异。[德国岛屿表](https://sbc.org.pl/Content/675776/PDF/iii5024-1938-00-0001.pdf) · [1920 年边界公报](https://library.fes.de/danzig/pdf/1920/1920-287.pdf) · [Kahlberg 与 Elbing 沿革](https://www.territorial.de/dawp/elbing/kahlbg.htm) · [波罗河界条文](https://eli.gov.pl/api/acts/DU/1937/19/text/O/D19370019.pdf)

检索 `1936 granica Polska Niemcy mapa Wolne Miasto Gdansk`、`Kahlberg Pröbbernau Grenze Danzig Ostpreußen 1920`、`1936 granica Polska Rumunia Czeremosz Kołomyja` 及 `Ostfriesische Inseln Borkum Juist Neuwerk Föhr Pellworm 1936 history`。同时定位到 1936 年行政地图与但泽图集的馆藏条目。MCP 查回 Danzig `10923`，比对两个州 `280`、`2729` 的 5 个陆省，按史料登记独立的 `DNZ` 并保留紫色归属边界。[1936 波兰行政地图馆藏](https://rcin.org.pl/dlibra/publication/41879/edition/29284/mapa-administracyjno-sadowa-rzeczypospolitej-polskiej-wacznadze-d-apposition-kartograf?language=pl) · [1936 但泽图集馆藏](https://pbc.gda.pl/dlibra/publication/2832/edition/3464?language=pl)

### 师级序列与驻地

| 对象 | 检索与核对 | 导入口径 |
|---|---|---|
| 德国师部 | 检索 `Tessin Formationsgeschichte 1933–1939 Divisionskommandos Herbst 1935`。用档案馆《Höhere Stäbe》PDF 第 4、5 页（印刷页 21、22）核对步兵师 1 至 24 及三个装甲师，PDF 第 6 页复核两个骑兵师在 1936 年 4 月撤销。第 1 步兵师 1936 年 2 月才迁到 Insterburg。用 1936 年 1 月捷方报告和学术回顾区分年初与春季扩编。 | 年初为 24 个步兵师、3 个装甲师、2 个骑兵师。第 1 步兵师落在 Königsberg `19906`；第 1 骑兵师 Potsdam `20575`，第 2 骑兵师 Breslau `9490`。[师部档案](https://www.bundesarchiv.de/assets/bundesarchiv/de/Downloads/PDFs_Milhist-Fachliteratur-Auszuege/Tessin_Formationsgeschichte_1933-39_-_Hoehere_Staebe.pdf#page=4) · [捷方情报史回顾](https://securitas-imperii-journal.com/wp-content/uploads/2021/05/SI_35_s12-49.pdf#page=14) |
| 德国下属编制 | 检索 `Formationsgeschichte der Wehrmacht Standorte Unterstellung Infanterieregimenter 15.10.1935`。打开档案馆全本，提取 PDF 第 118–142 页中 `15.10.35` 一列。按团番号纵向对齐，以末行的师编号和军区核对隶属。OCR 混淆的 16、57、66 团看了 PDF 第 120、130、132 页原图；第 47 团用同一表中 1935 列的归属。PDF 第 68 页核对 6 个装甲团，第 154、156 页核对骑兵旅。 | 团和旅进入各自师的 `orbat-native-v1` 编制树，保存逐条来源页。[档案馆全本](https://www.bundesarchiv.de/assets/bundesarchiv/de/Publikationen/BArch/Publikation_Schriften_Formationsgeschichte_Wehrmacht.pdf#page=118) |
| 波兰步兵 | 检索 `1936 Wojsko Polskie 30 dywizji piechoty sztab wykaz garnizonów`，通过波兰研究所 B.I.2a 第 26 页确认 30 个常备步兵师。用 Jan Pilżys 表 1 对照各师、团和驻地，并用参考了 1935 年军事年鉴的驻地表复校。第 8 师补第 32 团，第 29 师为第 41 团，和各自地方文献交叉核对。Pilżys 表有重复的 18 号行，第 19 师师部采用 Wilno 的军官经历及驻地考证，Nowa Wilejka 是其第 85 团驻地。 | 30 个步兵师含两个山地步兵师。每师收录三个步兵或山地团及其轻炮兵团。[研究所卷宗](https://pism.org.uk/img/archiwum/dokumenty/B/B_I_002ap1_1.pdf#page=26) · [Pilżys 表 1](https://paperity.org/p/97967629/piechota-polska-w-dwudziestoleciu-miedzywojennym-1918-1939-wybrane-probleny-organizacji) · [军事年鉴引文与驻地对照](https://www.academia.edu/93344252/The_History_of_the_Infantry_Uniforms_in_the_Polish_Armed_Forces_1918_1939) · [第 32 团](https://www.nowydwormaz.pl/plik%2C1380%2C32-pp-w-obronie-nd-pdf.pdf) · [第 41 团](https://pcr.uwb.edu.pl/ZNMW/files/ZNMW_1996_10_007.pdf) |
| 波兰骑兵 | 检索 `2 Dywizja Kawalerii Warszawa 1935 1936 skład`。波兰独立博物馆《Dzieje Kawalerii Polskiej》卷一 PDF 第 84、85、87 页记华沙师部、三个骑兵旅和 1937 年的改编。 | 本开局录入第 2 骑兵师及其第 1、12、13 旅，另外的独立旅待补。[骑兵史原页](https://muzn.pl/wp-content/uploads/2024/12/dd0e1cbb-dzieje-kawalerii-t-i.pdf#page=87) |

德波 60 个师部均核对省地址及归属。地名优先和原生省名比对，确认了 `Siedlce` 对应底图拼作 `Sieldce` 的 `19914`。Modlin 和 Jarosław 用坐标查询返回的原生 polygon 名义命中与所在州交叉核对，在仍有候选的情况下以 `inferred` 登记 `11774` 和 `12728`。组织节点与实际人数、装备分开，后者仍保留未知。各师驻地、团编号和德方精确页码可从同一记录重跑。[师级资料清单](../../../tools/scenarios/1936-de-pl-roster.json)

### 导入和验证方法

以旧节点导出和同节点 `world_view` 为输入。在准备命令上增加 `--continue-from runtime/scenario-1936/demo/world --out runtime/scenario-1936/opening/prepared`。将第一版导出复制到新的工作目录 `runtime/scenario-1936/opening/demo/world/`，放入新 `geo-reference.json`，用同一个 `submit-scenario.py --apply` 执行 244 条命令。清单使用原分支作为基点，保存到 `de-pl-1936-2`。史料对照文件含 2,340 个陆省的预期归属，两个已保存节点都可用同一范围诊断。[准备与导入脚本](../../../tools/prepare-1936-pilot.py) · [GM 提交脚本](../../../tools/submit-scenario.py)

回填后的德国、波兰、但泽归属分别为 1,428、907、5 省。比对同一份普通陆地邻接，德波两侧都有归属的边从 41 条增至 113 条，但泽与德国及波兰分别有 5、9 条邻接。该检查按原生省 ID 读取，不需要改省形。留档在 `runtime/scenario-1936/opening/border-edges.json`、`prepared/research.json` 和 `import-receipt.json`。

重启 GM MCP 后核对全部 60 个师部和 213 个下属节点。用 `world_territory_gaps` 分别按国家、州和全图查询；史料范围内旧节点有 192 个缺项，`de-pl-1936-2` 为 0。Chromium 检查粉色高亮、分页、点击定位和 Kielce 编制图，随后在 Windows 实测新节点及缺项接口。截图 `de-pl-1936-1-border.png`、`de-pl-1936-2-border.png` 使用同一地图倍率，及 `debug-before.png`、`debug-after.png` 留在 `runtime/scenario-1936/opening/`。
