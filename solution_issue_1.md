```python
import requests

def get_rewards(vault_balance, referral_rewards):
    url = "http://localhost:8118/rewards/v1"
    payload = {
        "balance": vault_balance,
        "referral_rewards": referral_rewards
    }
    response = requests.post(url, json=payload)
    return response.json()

# Пример использования
balance = 100
referral_rewards = 50
print(get_rewards(balance, referral_rewards))
```