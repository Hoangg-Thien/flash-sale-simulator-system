# How to Test Redis Distributed Lock Across Multiple Instances

## Step 1: Start PostgreSQL and Redis
Ensure that PostgreSQL and Redis containers are up and running:
```powershell
docker compose up postgres redis -d
```

---

## Step 2: Start 2 Spring Boot instances on two different ports
Open 2 separate PowerShell terminals:

- **Terminal 1 (Instance A — Port 8080):**
```powershell
$env:DB_PASSWORD="your_password"; $env:SERVER_PORT="8080"; ./mvnw spring-boot:run
```

- **Terminal 2 (Instance B — Port 8081):**
```powershell
$env:DB_PASSWORD="your_password"; $env:SERVER_PORT="8081"; ./mvnw spring-boot:run
```

---

## Step 3: Create a test product
Open a 3rd PowerShell terminal and create a test product with `stock = 1`:
```powershell
$body = @{ name = "iPhone Multi-Node Demo"; price = 20000000; initialStock = 1 } | ConvertTo-Json
$product = Invoke-RestMethod -Uri "http://localhost:8080/api/v1/products" -Method Post -ContentType "application/json" -Body $body
$productId = $product.id
Write-Host "Created Product ID: $productId with Stock = 1" -ForegroundColor Green
```

---

## Step 4: Send 20 concurrent requests alternating between both nodes (8080 & 8081)
Run this asynchronous PowerShell script using `HttpClient`:

```powershell
Add-Type -AssemblyName System.Net.Http

# (Optional) Reset product inventory stock to 1 in DB before testing
docker exec -i flashsale-db psql -U postgres -d flashsale -c "UPDATE inventory SET stock = 1 WHERE product_id = $productId;"

$client = [System.Net.Http.HttpClient]::new()

Write-Host "🚀 Sending 20 concurrent requests alternating between Port 8080 and Port 8081..." -ForegroundColor Cyan

# Create 20 concurrent tasks alternating across the 2 instances
$tasks = 1..20 | ForEach-Object {
    $port = if ($_ % 2 -eq 0) { 8080 } else { 8081 }
    $url = "http://localhost:$port/api/v1/orders"
    $json = "{`"productId`": $productId, `"quantity`": 1, `"lockMode`": `"REDIS`"}"
    $content = [System.Net.Http.StringContent]::new($json, [System.Text.Encoding]::UTF8, "application/json")
    
    [PSCustomObject]@{
        Id = $_
        Port = $port
        Task = $client.PostAsync($url, $content)
    }
}

# Wait for all requests to finish concurrently
[System.Threading.Tasks.Task]::WaitAll($tasks.Task)

# Output results from both instances
$tasks | ForEach-Object {
    $status = $_.Task.Result.StatusCode
    $color = if ($status -eq "Created") { "Green" } else { "DarkYellow" }
    Write-Host ("Request #{0,-2} -> Port {1} : {2}" -f $_.Id, $_.Port, $status) -ForegroundColor $color
}
```

---

## Step 5: Verify results

1. **HTTP Status Results:**
   - Exactly **1 request** succeeds with `Created (201)`.
   - All remaining **19 requests** are rejected with `Conflict (409)`.

2. **Verify Final Stock in Database:**
```powershell
docker exec -i flashsale-db psql -U postgres -d flashsale -c "SELECT id, product_id, stock FROM inventory WHERE product_id = $productId;"
```
   - The final stock must be exactly **`0`** (preventing oversell/race conditions).

3. **Inspect Logs across Terminals:**
   - The first instance to receive the request acquires the Distributed Lock (`lock:inventory:{productId}`) on Redis and decrements inventory.
   - The other instance encountering lock contention will log:
```text
RedisLockStrategy: Lock acquisition failed for product {productId} (or Insufficient stock)
```