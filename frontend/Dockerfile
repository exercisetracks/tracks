# Node 22, not 20, and the build breaks on anything older.
#
# scripts/build-map-style.mjs loads scripts/extensionless-resolver.mjs, which
# calls module.registerHooks() — added in Node 22.15. On Node 20 that import
# throws before a single module is bundled, so `npm run build` cannot run at
# all. It went unnoticed because `npm run dev` is plain `vite` and never
# invokes build:map-style: development worked while the production image could
# not be built, which is the failure that hides longest.
FROM node:24-alpine

WORKDIR /app

# Install build dependencies for rollup
RUN apk add --no-cache libc6-compat

# The lockfile, and `npm ci` rather than `npm install`. Two reasons:
#
# 1. Reproducibility. The lockfile is committed but was never copied in, so
#    every image build re-resolved the tree from scratch and could pick up
#    whatever had been published since — a released image built from the same
#    commit twice was not the same image.
# 2. `npm install` on npm 10.9.x crashes on this dependency graph
#    ("Cannot read properties of null (reading 'edgesOut')"). `npm ci` installs
#    the locked tree instead of re-solving it, and does not hit that path.
#
# patches/ is copied BEFORE the install, because patch-package runs as a
# postinstall hook. Copied afterwards with the rest of the source — as it was —
# the hook ran against a directory that did not exist yet, found nothing, and
# exited successfully, so the react-muscle-highlighter patch was silently
# absent from every image while working fine in local development.
COPY package.json package-lock.json ./
COPY patches ./patches
RUN npm ci

COPY . .

# Backend base URL baked into the build. Defaults to the relative "/api" so the
# image is origin-agnostic: the Caddy entry container routes /api/* to the
# backend, and apiBase.js resolves "/api" against whatever domain the operator
# serves from at runtime. Override with --build-arg VITE_API_URL=... for a
# split-host deployment.
ARG VITE_API_URL=/api
ENV VITE_API_URL=$VITE_API_URL

# Build the app for production
RUN npm run build

EXPOSE 3000

CMD ["npm", "run", "preview", "--", "--host", "0.0.0.0", "--port", "3000"]
