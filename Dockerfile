# A JDK and the Android SDK, deliberately no Gradle: builds run ./gradlew, so
# the wrapper is the only place a Gradle version is written down. A gradle:X
# image would put a second version in this tag, and the two drift.
FROM eclipse-temurin:21-jdk

ENV ANDROID_HOME=/opt/android-sdk \
    ANDROID_SDK_ROOT=/opt/android-sdk
ENV PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools

RUN set -eux; \
    apt-get update; \
    # git for the capture-log build stamp, python3 for verifyReleaseDexKeeps
    # and the checks under scripts/. The old gradle:X image supplied both;
    # a plain JDK base does not.
    apt-get install -y --no-install-recommends wget unzip ca-certificates git python3; \
    rm -rf /var/lib/apt/lists/*; \
    mkdir -p $ANDROID_HOME/cmdline-tools; \
    cd /tmp; \
    wget -q https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip; \
    unzip -q commandlinetools-linux-11076708_latest.zip -d $ANDROID_HOME/cmdline-tools; \
    mv $ANDROID_HOME/cmdline-tools/cmdline-tools $ANDROID_HOME/cmdline-tools/latest; \
    rm commandlinetools-linux-11076708_latest.zip; \
    yes | sdkmanager --licenses > /dev/null; \
    sdkmanager --install "platform-tools" "platforms;android-36" "build-tools;36.0.0" "platforms;android-37.0" "build-tools;37.0.0"; \
    chmod -R a+rwX $ANDROID_HOME

WORKDIR /workspace
