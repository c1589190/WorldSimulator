#!/usr/bin/env python3
"""Preview or save a scenario through the GM stdio MCP tools."""

import argparse
from collections import Counter
from concurrent.futures import ThreadPoolExecutor
from hashlib import sha256
import json
from pathlib import Path
from subprocess import PIPE, Popen, TimeoutExpired


class WorldMcp:
    def __init__(self, process, reader):
        self.process = process
        self.reader = reader
        self.sequence = 0

    def send(self, **fields):
        self.process.stdin.write(json.dumps({"jsonrpc": "2.0", **fields}) + "\n")
        self.process.stdin.flush()

    def call(self, method, params):
        self.sequence += 1
        self.send(id=self.sequence, method=method, params=params)
        raw = self.reader.submit(self.process.stdout.readline).result(timeout=120)
        if not raw:
            raise RuntimeError(f"MCP 在 {method} 返回前退出，见运行日志")
        response = json.loads(raw)
        if response.get("id") != self.sequence or "error" in response:
            raise RuntimeError(f"MCP {method} 返回错误: {response}")
        return response["result"]

    def tool(self, tool_name, **arguments):
        reply = self.call("tools/call", {"name": tool_name, "arguments": arguments})
        if reply.get("isError"):
            raise RuntimeError(f"{tool_name} 失败: {reply['content']}")
        return json.loads(reply["content"][0]["text"])

    def initialize(self):
        self.call("initialize", {
            "protocolVersion": "2025-03-26", "capabilities": {},
            "clientInfo": {"name": "worldsim-scenario-import", "version": "1"},
        })
        self.send(method="notifications/initialized", params={})


def preview(client, plan, token, report, save):
    base = plan["base"]
    base_hash = plan["baseHash"]
    commands = plan["commands"]
    before = client.tool("world_overview", node=base)
    if before["stateHash"] != base_hash:
        raise RuntimeError("基准节点与导入清单不一致")
    for expected in plan.get("geoChecks", []):
        result = client.tool("world_geo_lookup", node=base, name=expected["name"])
        report["geography"].append(result)
        if result["status"] != "resolved" or result["candidates"][0]["provinceId"] != expected["provinceId"]:
            raise RuntimeError(f"地名校对失败: {expected['name']}")
    opening = {"base": base, "expectedHash": base_hash}
    if fork := plan.get("forkBranch"):
        opening["forkBranch"] = fork
    draft = client.tool("edit_open", **opening)
    draft_id = draft["draftId"]
    version = draft["draftVersion"]
    for start in range(0, len(commands), 100):
        batch = commands[start:start + 100]
        common = {"draftId": draft_id, "expectedVersion": version, "commands": batch}
        result = client.tool("edit_apply", **common, requestId=f"{token}-preview-{start}", dryRun=True)
        report["batches"].append(result)
        applied = client.tool("edit_apply", **common, requestId=f"{token}-apply-{start}")
        if applied["stateHash"] != result["stateHash"]:
            raise RuntimeError(f"批次 {start // 100 + 1} 的应用结果与预览不一致")
        version = applied["draftVersion"]
        print(f"批次 {start // 100 + 1} · {len(batch)} 条 · 草稿版本 {version}", flush=True)
    if not save:
        client.tool("edit_discard", draftId=draft_id, expectedVersion=version)
        return
    saved = client.tool("edit_save", draftId=draft_id, expectedVersion=version, requestId=f"{token}-save",
                        tick=plan["tick"], baseHash=base_hash)
    report["saved"] = saved
    target = saved["node"]
    print(f"已保存 {target} · {saved['stateHash']}", flush=True)
    if saved["stateHash"] != report["batches"][-1]["stateHash"]:
        raise RuntimeError("保存节点的 hash 与最终预览不一致")
    owners = {}
    deployments = {}
    for command in commands:
        if command["op"] == "setTerritory" and command["field"] == "owner":
            assignment = command["assignment"]
            owners.update(dict.fromkeys(command["provinceIds"], assignment["countryId"] if assignment else None))
        elif command["op"] == "putUnit":
            unit = command["unit"]
            deployments[unit["id"]] = unit.get("provinceId")
        elif command["op"] == "deployUnit":
            deployments[command["unitId"]] = command.get("provinceId")
    by_country = Counter(country for country in owners.values() if country is not None)
    for country, count in by_country.items():
        result = client.tool("world_lookup", address=f"{target}/country/{country}")
        report["countries"][country] = result
        if result["ownedProvinces"] < count:
            raise RuntimeError(f"{country} 保存后的归属数少于清单已指定的省")
    for unit_id, province_id in deployments.items():
        result = client.tool("world_lookup", address=f"{target}/orbat/unit/{unit_id}")
        report["units"].append(result)
        expected = f"{target}/map/province/{province_id}" if province_id is not None else None
        if result.get("provinceAddress") != expected:
            raise RuntimeError(f"{unit_id} 保存后的部署省与清单不一致")
    report["verification"] = client.tool("timeline_verify")
    if client.tool("world_overview", node=base)["stateHash"] != base_hash:
        raise RuntimeError("基准节点发生变化")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", type=Path, required=True)
    parser.add_argument("--case", type=Path, required=True, help="包含 world/ 的运行目录")
    parser.add_argument("--plan", type=Path, required=True)
    parser.add_argument("--report", type=Path, required=True)
    parser.add_argument("--apply", action="store_true", help="保存预览完成的分支，默认丢弃内存草稿")
    args = parser.parse_args()
    raw = args.plan.read_bytes()
    plan = json.loads(raw)
    if not plan["commands"]:
        raise ValueError("导入清单没有命令")
    token = "scenario-" + sha256(raw).hexdigest()[:16]
    args.report.parent.mkdir(parents=True, exist_ok=True)
    report = {"base": plan["base"], "date": plan.get("date"), "mode": "save" if args.apply else "preview",
              "geography": [], "batches": [], "countries": {}, "units": []}
    try:
        with args.report.with_suffix(".stderr.log").open("w", encoding="utf-8") as log, ThreadPoolExecutor(1) as reader:
            process = Popen(["java", "-Xmx4g", "-jar", str(args.jar.resolve()), "mcp"], cwd=args.case,
                            stdin=PIPE, stdout=PIPE, stderr=log, encoding="utf-8", text=True)
            try:
                client = WorldMcp(process, reader)
                client.initialize()
                preview(client, plan, token, report, args.apply)
                process.stdin.close()
                if process.wait(timeout=20):
                    raise RuntimeError("MCP 异常退出，见运行日志")
            finally:
                if process.poll() is None:
                    process.terminate()
                    try:
                        process.wait(timeout=8)
                    except TimeoutExpired:
                        process.kill()
                        process.wait()
                process.stdout.close()
        report["status"] = "passed"
    except Exception as error:
        report["status"] = "failed"
        report["error"] = str(error)
        raise
    finally:
        args.report.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
