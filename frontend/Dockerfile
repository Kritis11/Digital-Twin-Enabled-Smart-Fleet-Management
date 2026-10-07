# Build stage: the Angular production build (relative /api and /ws URLs, see environment.prod.ts).
FROM node:24-alpine AS build
WORKDIR /build
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
RUN npx ng build --configuration production

# Runtime stage: nginx serves the build and is the only public entry point: it terminates TLS and
# proxies /api and /ws to the backend. The nginx master runs as root (ports 80/443, certificate key);
# its workers, which handle the traffic, run as the unprivileged nginx user.
FROM nginx:1.30.5-alpine
RUN apk add --no-cache openssl
COPY nginx/default.conf.template /etc/nginx/templates/default.conf.template
COPY nginx/15-certificate.sh /docker-entrypoint.d/15-certificate.sh
RUN chmod +x /docker-entrypoint.d/15-certificate.sh
COPY --from=build /build/dist/frontend/browser /usr/share/nginx/html
# Only these two are substituted in the template; nginx's own $variables are left alone.
ENV NGINX_ENVSUBST_FILTER="DOMAIN|LOGIN_RATE_PER_MINUTE" LOGIN_RATE_PER_MINUTE=10
EXPOSE 80 443
HEALTHCHECK --interval=15s --timeout=5s --start-period=20s --retries=5 \
  CMD wget -q -O /dev/null --no-check-certificate https://127.0.0.1/healthz || exit 1
