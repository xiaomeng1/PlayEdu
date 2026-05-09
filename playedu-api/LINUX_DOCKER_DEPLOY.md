# PlayEdu CentOS Stream 9 Docker 部署说明

本文档面向 `CentOS Stream 9`，并针对这次安装过程中遇到的实际问题做了收敛，重点避免以下故障再次出现：

- `download.docker.com` 访问不稳定导致 `SSL connect error`
- Docker 安装成功后，`docker.service` 因系统库冲突启动失败
- 执行 `docker compose up -d` 时提示无法连接 Docker daemon
- 当前用户未加入 `docker` 组，访问 `/var/run/docker.sock` 失败

## 1. 部署前提

确认以下条件：

- 系统版本为 `CentOS Stream 9`
- 使用具备 `sudo` 权限的用户
- 服务器可以访问互联网
- 已开放端口：
  - `22`
  - `9700`
  - `9800`
  - `9801`
  - `9900`

## 2. 安装 Docker 前先同步系统包

这一步很重要。你这次实际遇到的 `dockerd` 启动失败，根因就是系统里的 `libnftables` / `libnftnl` 依赖版本不一致。

先执行：

```bash
sudo dnf clean all
sudo rm -rf /var/cache/dnf
sudo dnf makecache
sudo dnf update -y
sudo dnf distro-sync -y
sudo reboot
```

机器重启后再继续下面步骤。

## 3. 安装 Docker

推荐直接使用阿里云 Docker CE 镜像源，避免访问官方仓库时出现网络或 SSL 问题。

按顺序执行：

```bash
sudo dnf -y remove docker docker-client docker-client-latest docker-common docker-latest docker-latest-logrotate docker-logrotate docker-engine
sudo dnf -y install dnf-plugins-core git
sudo rm -f /etc/yum.repos.d/docker-ce.repo
sudo tee /etc/yum.repos.d/docker-ce.repo > /dev/null <<'EOF'
[docker-ce-stable]
name=Docker CE Stable - $basearch
baseurl=https://mirrors.aliyun.com/docker-ce/linux/centos/9/$basearch/stable
enabled=1
gpgcheck=1
gpgkey=https://mirrors.aliyun.com/docker-ce/linux/centos/gpg
EOF
sudo dnf clean all
sudo rm -rf /var/cache/dnf
sudo dnf makecache
sudo dnf install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
```

## 4. 安装后先校验服务，不要直接跑 compose

安装完 Docker 后，先做服务启动和状态校验：

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now containerd
sudo systemctl enable --now docker
sudo systemctl status docker --no-pager
sudo docker version
sudo docker compose version
```

如果 `status` 中看到 `active (running)`，再继续后面的部署。

## 5. 如果安装 Docker 时遇到 SSL 错误

如果你看到类似报错：

```text
Curl error (35): SSL connect error
OpenSSL SSL_connect: Connection reset by peer
```

这通常不是命令写错，而是服务器到仓库源的网络连通性有问题。

先检查系统时间和镜像源可达性：

```bash
date
timedatectl status
curl -I https://mirrors.aliyun.com/docker-ce/linux/centos/9/x86_64/stable/repodata/repomd.xml
```

如果系统时间不对，先校时：

```bash
sudo timedatectl set-timezone Asia/Shanghai
sudo timedatectl set-ntp true
```

然后重新执行第 3 节的安装命令。

## 6. 如果 Docker 服务启动失败

如果执行下面命令失败：

```bash
sudo systemctl enable --now docker
```

不要继续执行 `docker compose up -d`，先看日志：

```bash
sudo systemctl status docker --no-pager -l
sudo journalctl -u docker.service --no-pager -n 100
```

### 6.1 常见故障：libnftables 符号错误

如果日志中出现类似报错：

```text
/usr/bin/dockerd: symbol lookup error: /lib64/libnftables.so.1:
undefined symbol: nftnl_set_elem_nlmsg_build_payload, version LIBNFTNL_11
```

说明系统中的 `libnftables` / `libnftnl` 依赖版本不一致。按下面步骤修复：

```bash
sudo dnf clean all
sudo rm -rf /var/cache/dnf
sudo dnf makecache
sudo dnf update -y
sudo dnf reinstall -y nftables libnftnl iptables
sudo dnf distro-sync -y
sudo reboot
```

机器重启后重新执行：

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now containerd
sudo systemctl enable --now docker
sudo systemctl status docker --no-pager
sudo docker version
```

只有在 `docker.service` 确认正常运行后，后面的 `docker compose` 才会成功。

## 7. 处理 docker.sock 权限问题

如果你想直接执行 `docker compose up -d`，而不是每次都写 `sudo`，需要把当前用户加入 `docker` 组：

```bash
sudo usermod -aG docker $USER
newgrp docker
docker version
docker compose version
```

如果你还没重新登录，或者 `newgrp docker` 没生效，就继续使用带 `sudo` 的命令。

## 8. 拉取项目并启动

拉取项目：

```bash
git clone --branch main https://gitee.com/playeduxyz/playedu.git
cd playedu
```

建议先确认当前目录下有 `compose.yml`：

```bash
ls
```

然后启动：

```bash
sudo docker compose up -d
```

如果你已经确认当前用户的 `docker` 组权限生效，再改用：

```bash
docker compose up -d
```

## 9. 访问地址

把下面地址中的 `your-server-ip` 替换成你的服务器公网 IP 或内网 IP：

- 管理后台：`http://your-server-ip:9900`
- PC 端：`http://your-server-ip:9800`
- H5 端：`http://your-server-ip:9801`
- API：`http://your-server-ip:9700`

默认管理员账号：

- 账号：`admin@playedu.xyz`
- 密码：`playedu`

首次登录后应立即修改密码。

## 10. 建议增加 .env 文件

建议在项目根目录创建 `.env` 文件：

```env
PLAYEDU_API_PORT=9700
PLAYEDU_PC_PORT=9800
PLAYEDU_H5_PORT=9801
PLAYEDU_ADMIN_PORT=9900
MYSQL_PORT=23307
PLAYEDU_JWT_KEY=请替换为一串足够长的随机字符串
```

创建完成后重新启动服务：

```bash
sudo docker compose down
sudo docker compose up -d
```

## 11. 常用运维命令

查看容器状态：

```bash
cd playedu
sudo docker compose ps
```

查看日志：

```bash
cd playedu
sudo docker compose logs -f
```

重启服务：

```bash
cd playedu
sudo docker compose restart
```

停止服务：

```bash
cd playedu
sudo docker compose down
```

查看镜像：

```bash
sudo docker images
```

查看数据卷：

```bash
sudo docker volume ls
```

## 12. 升级方式

更新代码后执行：

```bash
cd playedu
git pull
sudo docker compose down
sudo docker compose up -d
```

如果镜像有变化但未自动更新，可以执行：

```bash
cd playedu
sudo docker compose pull
sudo docker compose up -d
```

## 13. 项目自带服务说明

项目根目录中的 `compose.yml` 会启动以下服务：

- `mysql`
- `playedu`

默认暴露端口如下：

- API：`9700`
- PC：`9800`
- H5：`9801`
- Admin：`9900`

## 14. 参考

- Docker 官方 CentOS 安装文档：https://docs.docker.com/engine/install/centos/
- 阿里云 Docker CE 镜像：https://developer.aliyun.com/mirror/docker-ce

## 15. 一次性执行版

如果你希望尽量减少漏步骤，可以直接按下面顺序执行：

```bash
sudo dnf clean all
sudo rm -rf /var/cache/dnf
sudo dnf makecache
sudo dnf update -y
sudo dnf distro-sync -y
sudo reboot
```

重启后继续执行：

```bash
sudo dnf -y remove docker docker-client docker-client-latest docker-common docker-latest docker-latest-logrotate docker-logrotate docker-engine
sudo dnf -y install dnf-plugins-core git
sudo rm -f /etc/yum.repos.d/docker-ce.repo
sudo tee /etc/yum.repos.d/docker-ce.repo > /dev/null <<'EOF'
[docker-ce-stable]
name=Docker CE Stable - $basearch
baseurl=https://mirrors.aliyun.com/docker-ce/linux/centos/9/$basearch/stable
enabled=1
gpgcheck=1
gpgkey=https://mirrors.aliyun.com/docker-ce/linux/centos/gpg
EOF
sudo dnf clean all
sudo rm -rf /var/cache/dnf
sudo dnf makecache
sudo dnf install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
sudo systemctl daemon-reload
sudo systemctl enable --now containerd
sudo systemctl enable --now docker
sudo docker version
sudo docker compose version
git clone --branch main https://gitee.com/playeduxyz/playedu.git
cd playedu
sudo docker compose up -d
```
