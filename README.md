_# Zstd Compresser - 客户端模组

Minecraft 客户端模组（Architectury 1.20.1），在加密前拦截网络流量，合并小包，使用 Zstandard (zstd) 压缩，并显示实时压缩统计。

---

## ⚠️ 仅限客户端

**本模组仅用于客户端。请勿安装到服务端。** 服务端压缩由独立的 Velocity 插件 `zstd_velocity` 处理。

---

## 功能

- **小包合并与 Zstd 压缩**：用 Zstandard（压缩等级 9、32MB 滑动窗口）替代原版 Zlib
- **字典同步**：登录时从 Velocity 代理接收训练好的 zstd 字典
- **实时 HUD**：左上角显示 TX/RX 压缩速率（按 F8 开关）
- **自适应跳过**：Zstd 开销大于收益时自动跳过压缩
- **全配置化**：YAML 配置文件

---

## 运行要求

- Minecraft 1.20.1
- Fabric Loader 0.16+ **或** Forge 47+
- Fabric API（仅 Fabric）
- Architectury API 9+
- Java 17

---

## 安装

1. 下载对应平台的 JAR（Fabric: `zstd_compresser-fabric-<version>.jar` / NeoForge: `zstd_compresser-neoforge-<version>.jar`）
2. 放入 `mods/` 目录
3. 启动游戏 —— 首次运行自动生成 `config/zstd_compresser.yml`
4. 游戏内按 **F8** 切换压缩 HUD 显示

---

## 配置文件

```yaml
compression:
  level: 9
  window_log: 25
  batch_max_bytes: 65536
  flush_interval_ms: 10

display:
  hud_enabled: false    # HUD 开关（F8 运行时切换）

logging:
  stats_enabled: false
  stats_interval_sec: 10
  debug: false
```

---

## 按键

| 按键 | 功能          |
|----|-------------|
| F8 | 切换压缩 HUD 显示 |

可在 Minecraft 控件设置 → "Zstd 压缩器" 分类中修改按键。

---

## License

本项目采用 [AGPL-3.0 License](LICENSE) 开源许可。
