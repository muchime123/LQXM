# Setup LAN Access for Product Archive System
Write-Host "=== Configuring LAN Access ===" -ForegroundColor Cyan

# 1. Allow backend port 8081
Write-Host "Configuring firewall rule for backend port 8081..." -ForegroundColor Yellow
New-NetFirewallRule -DisplayName "Product Archive Backend 8081" -Direction Inbound -Protocol TCP -LocalPort 8081 -Action Allow -ErrorAction SilentlyContinue
Write-Host "OK: Backend port 8081 allowed" -ForegroundColor Green

# 2. Allow frontend port 5173
Write-Host "Configuring firewall rule for frontend port 5173..." -ForegroundColor Yellow
New-NetFirewallRule -DisplayName "Product Archive Frontend 5173" -Direction Inbound -Protocol TCP -LocalPort 5173 -Action Allow -ErrorAction SilentlyContinue
Write-Host "OK: Frontend port 5173 allowed" -ForegroundColor Green

# 3. Get local IP addresses
$ipConfig = Get-NetIPAddress -AddressFamily IPv4 | Where-Object { $_.InterfaceAlias -notlike "*Loopback*" } | Select-Object IPAddress
Write-Host "`n=== Local IP Addresses ===" -ForegroundColor Cyan
foreach ($ip in $ipConfig) {
    Write-Host "  $($ip.IPAddress)" -ForegroundColor White
}

Write-Host "`n=== Access URLs ===" -ForegroundColor Cyan
Write-Host "  Frontend: http://[YOUR_IP]:5173" -ForegroundColor White
Write-Host "  Backend:  http://[YOUR_IP]:8081" -ForegroundColor White
Write-Host "`nConfiguration complete! Please restart frontend and backend services." -ForegroundColor Green
