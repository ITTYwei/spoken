curl https://apis.itedus.cn/v1/chat/completions -H "Content-Type: application/json" -H "Authorization: Bearer sk-****" -d '{
  "model": "gpt-4o",
  "messages": [
    {
      "role": "user",
      "content": "1+1"
    }
  ]
}'