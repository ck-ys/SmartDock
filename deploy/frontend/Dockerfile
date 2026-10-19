FROM nginx:1.27-alpine
ENV TZ=Asia/Shanghai
COPY deploy/frontend/nginx.conf /etc/nginx/conf.d/default.conf
COPY frontend/dist/ /usr/share/nginx/html/
