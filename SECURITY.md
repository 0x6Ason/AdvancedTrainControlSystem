# Security

ATCS runs inside a Minecraft server and, when the web editor is switched on
(`integrations.editor.enabled`), also answers HTTP on the address and port you give it. A problem
that lets someone edit a network, read files or run anything without a valid editor link belongs
here.

## Reporting

Report it privately through GitHub: the repository's **Security** tab, **Report a vulnerability**.
Do not open a public issue. Say what an attacker can do, how (a request, a link, a sign text), and
which version you tried it on. You will get an answer within a week.

## Supported versions

Fixes go into the newest release. Use it before reporting, if you can.

## Running the editor safely

The editor is off by default and binds to `127.0.0.1`. To reach it from elsewhere, put a reverse
proxy with HTTPS in front of it rather than binding it to a public address. Editor links work once
and sessions end after `integrations.editor.session-minutes` without use.
