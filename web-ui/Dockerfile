FROM node:22.19-alpine AS build
WORKDIR /workspace
COPY web-ui/package.json web-ui/package-lock.json ./web-ui/
WORKDIR /workspace/web-ui
RUN npm ci
COPY web-ui .
COPY docs /workspace/docs
RUN npm run build

FROM nginxinc/nginx-unprivileged:1.27-alpine
COPY web-ui/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /workspace/web-ui/dist /usr/share/nginx/html
EXPOSE 8080
