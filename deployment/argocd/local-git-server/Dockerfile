# Read-only git server for a local kind cluster when the GitHub repo is private and no token is used.
# ArgoCD reads env/dev from it over smart HTTP: http://capstone-git:8000/cgi-bin/git/capstone.git
FROM python:3.12-alpine
RUN apk add --no-cache git git-daemon \
    && git config --system safe.directory '*' \
    && git config --system http.receivepack false
COPY git /srv/cgi-bin/git
RUN chmod +x /srv/cgi-bin/git
RUN addgroup -S -g 10001 git && adduser -S -u 10001 -G git git
WORKDIR /srv
USER 10001
EXPOSE 8000
CMD ["python", "-m", "http.server", "--cgi", "8000"]
