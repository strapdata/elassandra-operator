ARG baseImage
ARG driverAddons

FROM ${baseImage}
COPY ${driverAddons} /usr/local/lib/

# Workaround to include the address translator in the reaper classpath
COPY entrypoint.sh /usr/local/bin