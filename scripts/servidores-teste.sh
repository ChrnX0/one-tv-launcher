#!/usr/bin/env bash
# Sobe FTP, FTPS, SFTP e NFS de teste NESTA máquina (Ubuntu/Debian, como root; ex.: o container da nuvem) para o
# PastasServidoresTest. 14/11/2026. Não usar num PC de verdade: cria o usuário "teste" e importa uma CA de teste no Java.
# Uso: sudo bash scripts/servidores-teste.sh && ONETV_SERVIDORES=1 LC_ALL=C.UTF-8 ./gradlew testReleaseUnitTest --tests '*PastasServidoresTest'
set -euo pipefail
DEBIAN_FRONTEND=noninteractive apt-get install -y -qq openssh-server nfs-ganesha nfs-ganesha-vfs rpcbind openssl >/dev/null
pip install -q pyftpdlib pyopenssl
export LC_ALL=C.UTF-8
rm -rf /srv/dados && mkdir -p "/srv/dados/Filmes/Série A" /srv/dados/.oculto
head -c 300000 /dev/urandom > "/srv/dados/Filmes/vídeo 1.mkv"; echo foto > /srv/dados/Filmes/capa.jpg; echo x > "/srv/dados/Filmes/Série A/ep1.mp4"
chmod -R a+rX /srv/dados
id teste >/dev/null 2>&1 || useradd -m teste; echo 'teste:Senh4!' | chpasswd
# TLS: CA de teste + certificado de "localhost" (pelo IP 127.0.0.1 o nome não bate: o teste confere que recusa).
mkdir -p /srv/tls && cd /srv/tls
openssl req -x509 -newkey rsa:2048 -nodes -days 30 -subj "/CN=CA teste onetv" -keyout ca.key -out ca.crt 2>/dev/null
openssl req -newkey rsa:2048 -nodes -subj "/CN=localhost" -keyout srv.key -out srv.csr 2>/dev/null
printf "subjectAltName=DNS:localhost\n" > ext
openssl x509 -req -in srv.csr -CA ca.crt -CAkey ca.key -CAcreateserial -days 30 -extfile ext -out srv.crt 2>/dev/null
cat srv.crt srv.key > srv.pem
CACERTS="${CACERTS:-/etc/ssl/certs/java/cacerts}"
keytool -delete -alias onetv-teste -keystore "$CACERTS" -storepass changeit >/dev/null 2>&1 || true
keytool -importcert -noprompt -alias onetv-teste -file ca.crt -keystore "$CACERTS" -storepass changeit >/dev/null
# FTP (2121) e FTPS explícito (2122)
cat > /srv/ftp.py <<'P'
import sys
from pyftpdlib.authorizers import DummyAuthorizer
from pyftpdlib.handlers import FTPHandler, TLS_FTPHandler
from pyftpdlib.servers import FTPServer
from OpenSSL import SSL
a = DummyAuthorizer(); a.add_user("teste", "Senh4!", "/srv/dados", perm="elr"); a.add_anonymous("/srv/dados")
if sys.argv[1] == "tls":
    class H(TLS_FTPHandler): pass
    H.certfile = "/srv/tls/srv.pem"; H.ssl_protocol = SSL.TLS_METHOD; H.tls_control_required = True; H.tls_data_required = True
    porta, pp = 2122, range(30100, 30200)
else:
    class H(FTPHandler): pass
    porta, pp = 2121, range(30000, 30100)
H.authorizer = a; H.passive_ports = pp
FTPServer(("127.0.0.1", porta), H).serve_forever()
P
nohup python3 /srv/ftp.py plain >/srv/ftp1.log 2>&1 &
nohup python3 /srv/ftp.py tls >/srv/ftp2.log 2>&1 &
# SFTP (2222)
mkdir -p /run/sshd; ssh-keygen -A >/dev/null
/usr/sbin/sshd -p 2222 -o PasswordAuthentication=yes -o KbdInteractiveAuthentication=yes
# NFS v3: /srv/dados aceita porta alta; /srv/tls exige porta baixa (o padrão dos NAS) para testar a mensagem de erro.
cat > /srv/ganesha.conf <<'G'
NFS_CORE_PARAM { Protocols = 3; NFS_Port = 2049; MNT_Port = 20048; Enable_NLM = false; Enable_RQUOTA = false; }
NFSV4 { Graceless = true; }
EXPORT { Export_Id = 1; Path = /srv/dados; Pseudo = /srv/dados; Protocols = 3; Transports = TCP; Access_Type = RO; Squash = No_Root_Squash; SecType = sys; PrivilegedPort = false; FSAL { Name = VFS; } }
EXPORT { Export_Id = 2; Path = /srv/tls; Pseudo = /srv/tls; Protocols = 3; Transports = TCP; Access_Type = RO; Squash = No_Root_Squash; SecType = sys; PrivilegedPort = true; FSAL { Name = VFS; } }
LOG { Default_Log_Level = WARN; }
G
rpcbind -w 2>/dev/null || true
mkdir -p /var/run/ganesha /var/lib/nfs/ganesha
nohup ganesha.nfsd -F -f /srv/ganesha.conf -L /srv/ganesha.log -N NIV_WARN >/dev/null 2>&1 &
sleep 3; echo "servidores de teste no ar"
