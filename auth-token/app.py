import re
import secrets
import smtplib
import time
from email.message import EmailMessage
from flask import Flask, jsonify, request, send_file
REMET = "gerdauassistente@gmail.com"
SENHA = "trkz bogu fmsv tzmb"
VALIDADE = 300
INTERVALO = 30
MAX_TENTATIVAS = 5
app = Flask(__name__)
codigos = {}
def dv(nums):
    d = sum(n * p for n, p in zip(nums, range(2, len(nums) + 2))) % 11
    return 0 if d == 10 else d
def tokengen():
    nums = [secrets.randbelow(10) for _ in range(8)]
    nums.append(dv(nums))
    nums.append(dv(nums))
    return "".join(map(str, nums))
def enviar_email(dest, token):
    msg = EmailMessage()
    msg["From"], msg["To"], msg["Subject"] = REMET, dest, "Código de verificação"
    msg.set_content(f"""Olá, Recebemos uma solicitação de verificação para sua conta.\n\nUtilize o código abaixo para concluir o processo:\n
Código de verificação: {token}\n\nEste código ficará disponível por {VALIDADE // 60} minutos. Após esse período, será necessário solicitar um novo código.
\n\nAvisos  de segurança:\n
- Nunca compartilhe este código com outras pessoas.\n- Nossa equipe nunca solicitará seu código de verificação por telefone, mensagem ou e-mail.
- Se você não solicitou este código, ignore esta mensagem. Nenhuma ação adicional será necessária.\n- Não responda a este e-mail, pois esta caixa de entrada não é monitorada.\n
A segurança da sua conta é importante para nós.
\nAtenciosamente,
Equipe Gerdau\n\n\n\n\n""")
    with smtplib.SMTP_SSL("smtp.gmail.com", 465) as s:
        s.login(REMET, SENHA)
        s.send_message(msg)
def resposta(ok, msg="", status=200):
    return jsonify(ok=ok, msg=msg), status
@app.get("/")
def index():
    return send_file("index.html")
@app.post("/enviar")
def enviar():
    email = str((request.get_json(silent=True) or {}).get("email", "")).strip().lower()
    if not re.fullmatch(r"[a-z0-9._%+-]+@gmail\.com", email):
        return resposta(False, "Informe um endereço @gmail.com válido.", 400)
    atual = codigos.get(email)
    if atual and time.time() - atual["criado"] < INTERVALO:
        return resposta(False, f"Aguarde {INTERVALO} segundos para pedir outro código.", 429)
    token = tokengen()
    try:
        enviar_email(email, token)
    except smtplib.SMTPException:
        return resposta(False, "Não foi possível enviar o e-mail. Tente novamente.", 500)
    codigos[email] = {"token": token, "criado": time.time(), "tentativas": 0}
    return resposta(True)
@app.post("/verificar")
def verificar():
    d = request.get_json(silent=True) or {}
    email = str(d.get("email", "")).strip().lower()
    codigo = str(d.get("codigo", "")).strip()
    item = codigos.get(email)
    if not item or time.time() - item["criado"] > VALIDADE:
        codigos.pop(email, None)
        return resposta(False, "Código expirado. Solicite um novo.", 400)
    item["tentativas"] += 1
    if item["tentativas"] > MAX_TENTATIVAS:
        codigos.pop(email)
        return resposta(False, "Muitas tentativas. Solicite um novo código.", 429)
    if not secrets.compare_digest(codigo.encode(), item["token"].encode()):
        return resposta(False, "Código inválido. Confira o e-mail e tente de novo.", 400)
    codigos.pop(email)
    return resposta(True, "Autenticação concluída.")
if __name__ == "__main__":
    app.run(debug=True)