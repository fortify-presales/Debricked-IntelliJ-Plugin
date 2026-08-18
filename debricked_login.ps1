$body = @{
    _username = "klee2+demo@opentext.com"
    _password = "F)rt1fy!"
}

$response = Invoke-RestMethod `
    -Uri "https://debricked.com/api/login_check" `
    -Method Post `
    -Body $body `
    -ContentType "application/x-www-form-urlencoded"

$token = $response.token
"Bearer $token" | Set-Content -Path "token.txt"