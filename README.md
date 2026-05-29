# Zstd Compresser - 客户端模组

Minecraft 客户端模组（Architectury 1.20.1），在加密前拦截网络流量，合并小包，使用 Zstandard (zstd) 压缩，并显示实时压缩统计。

---

## 注意

**本模组仅用于客户端。请勿安装到服务端。** 服务端压缩由独立的 Velocity 插件 `zstd_velocity` 处理。

---

## 功能

- **兼容原版**：未安装 Velocity 插件时自动退回原版 Zlib
- **Zstd 压缩**：Zstandard（压缩等级 9、32MB 滑动窗口）替代原版 Zlib
- **字典支持**：登录时从 Velocity 代理接收训练好的 zstd 字典（编解码双端）
- **实时 HUD**：左上角显示 TX/RX 压缩速率（按 F8 开关）
- **自适应跳过**：Zstd 开销大于收益时自动跳过压缩
- **全配置化**：YAML 配置文件

---

## 运行要求

- Minecraft 1.20.1
- Fabric Loader 0.16+ 或 Forge 47+
- Fabric API（仅 Fabric）
- Architectury API 9+
- Java 17

---

## 安装

1. 下载对应平台的 JAR（Fabric / Forge）
2. 放入 `mods/` 目录
3. 启动游戏 —— 首次运行自动生成 `config/zstd_compresser.yml`
4. 游戏内按 **F8** 切换压缩 HUD 显示

---

## 配置文件

```yaml
compression:
  level: 9               # Zstd 压缩等级 (1-22)
  window_log: 25          # 滑动窗口 2^N 字节 (25 = 32MB)

display:
  hud_enabled: false      # HUD 开关（F8 运行时切换）

logging:
  stats_enabled: false    # TX/RX 统计日志
  stats_interval_sec: 10  # 统计输出间隔 (秒)
  debug: false            # 调试日志（管道详情、参数）
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
