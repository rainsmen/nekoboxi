#!/bin/bash
# GitHub Actions 编译状态检查脚本

REPO="rainsmen/nekoboxi"
RUN_ID="27481974837"
TOKEN="${GITHUB_TOKEN:-}"  # 从环境变量读取，或留空使用公开API

echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "   GitHub Actions 编译状态检查"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo

# 获取运行状态
if [ -n "$TOKEN" ]; then
  response=$(curl -s -H "Authorization: token $TOKEN" \
    -H "Accept: application/vnd.github.v3+json" \
    "https://api.github.com/repos/$REPO/actions/runs/$RUN_ID")
else
  # 无token时使用公开API（有速率限制）
  response=$(curl -s -H "Accept: application/vnd.github.v3+json" \
    "https://api.github.com/repos/$REPO/actions/runs/$RUN_ID")
fi

status=$(echo "$response" | jq -r '.status')
conclusion=$(echo "$response" | jq -r '.conclusion')
created_at=$(echo "$response" | jq -r '.created_at')
updated_at=$(echo "$response" | jq -r '.updated_at')

# 状态图标
case "$status" in
  "queued")
    icon="⏳"
    status_text="排队中"
    ;;
  "in_progress")
    icon="🔄"
    status_text="进行中"
    ;;
  "completed")
    if [ "$conclusion" == "success" ]; then
      icon="✅"
      status_text="成功完成"
    else
      icon="❌"
      status_text="失败"
    fi
    ;;
  *)
    icon="❓"
    status_text="未知"
    ;;
esac

echo "$icon 状态: $status_text"
echo

if [ "$status" == "completed" ]; then
  if [ "$conclusion" == "success" ]; then
    echo "🎉 编译成功！"
    echo
    echo "📦 下载 APK："
    echo "   https://github.com/$REPO/actions/runs/$RUN_ID"
    echo
    echo "💡 测试步骤："
    echo "   1. 下载 arm64-v8a APK（适用于大多数现代设备）"
    echo "   2. 安装到测试设备"
    echo "   3. 配置 Naive outbound 时添加: \"connection_warmup\": true"
    echo "   4. 启动 VPN 后立即访问 x.com 测试性能"
    echo "   5. 对比开启/关闭 connection_warmup 的加载速度"
    echo
  else
    echo "❌ 编译失败"
    echo "   查看详情: https://github.com/$REPO/actions/runs/$RUN_ID"
    echo
  fi
elif [ "$status" == "in_progress" ]; then
  echo "⏰ 编译正在进行，请稍候..."
  echo
  echo "   预计时间: 15-20分钟"
  echo "   开始时间: $created_at"
  echo "   最后更新: $updated_at"
  echo
  echo "🔗 实时查看: https://github.com/$REPO/actions/runs/$RUN_ID"
  echo
fi

echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
