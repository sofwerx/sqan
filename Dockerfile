FROM openjdk:8-jdk-stretch

ENV sdk_version=sdk-tools-linux-4333796.zip
ENV ANDROID_HOME=/android
ENV appdir=/sqan
ENV testdir=/sqan/testing/sqan

RUN apt-get update
RUN apt-get install -y wget git unzip

RUN mkdir -p ${ANDROID_HOME}
WORKDIR ${ANDROID_HOME}

RUN wget -q https://dl.google.com/android/repository/${sdk_version} \
 && unzip -q ${sdk_version} \
 && rm ${sdk_version}

#ENV ANDROID_NDK_HOME=${ANDROID_HOME}/android-ndk-${ANDROID_NDK_VERSION}
ENV PATH=${ANDROID_HOME}/tools:${ANDROID_HOME}/tools/bin:${ANDROID_HOME}/platform-tools:${ANDROID_NDK_HOME}:$PATH

RUN mkdir -p ${ANDROID_HOME}/licenses \
 && touch ${ANDROID_HOME}/licenses/android-sdk-license \
 && echo "\n8933bad161af4178b1185d1a37fbf41ea5269c55" >> $ANDROID_HOME/licenses/android-sdk-license \
 && echo "\nd56f5187479451eabf01fb78af6dfcb131a6481e" >> $ANDROID_HOME/licenses/android-sdk-license \
 && echo "\ne6b7c2ab7fa2298c15165e9583d0acf0b04a2232" >> $ANDROID_HOME/licenses/android-sdk-license \
 && echo "\n24333f8a63b6825ea9c5514f83c2829b004d1fee" >> $ANDROID_HOME/licenses/android-sdk-license \
 && echo "\n84831b9409646a918e30573bab4c9c91346d8abd" > $ANDROID_HOME/licenses/android-sdk-preview-license \
 && echo "\nd975f751698a77b662f1254ddbeed3901e976f5a" > $ANDROID_HOME/licenses/intel-android-extra-license

RUN yes | sdkmanager --licenses > /dev/null 2>&1
RUN yes | sdkmanager "platforms;android-28"
RUN mkdir -p ${ANDROID_HOME}/.android \
 && touch ~/.android/repositories.cfg ${ANDROID_HOME}/.android/repositories.cfg
RUN yes | sdkmanager "build-tools;28.0.3" > /dev/null 2>&1
RUN yes | sdkmanager "extras;android;m2repository" > /dev/null 2>&1

RUN DEBIAN_FRONTEND=noninteractive apt-get install -y locales
RUN sed -i -e 's/# en_US.UTF-8 UTF-8/en_US.UTF-8 UTF-8/' /etc/locale.gen && \
    DEBIAN_FRONTEND=noninteractive dpkg-reconfigure --frontend=noninteractive locales && \
    update-locale LANG=en_US.UTF-8

ENV LANG=en_US.UTF-8 LC_ALL=en_US.UTF-8

RUN mkdir -p ${appdir}
WORKDIR ${appdir}

COPY . .
RUN sed -i -e 's/^android {/android {\n lintOptions {\n    abortOnError false\n  }/' build.gradle
RUN chmod og+x ./gradlew
RUN ./gradlew build

WORKDIR ${testdir}
RUN sed -i -e 's/^android {/android {\n lintOptions {\n    abortOnError false\n  }/' build.gradle
RUN chmod og+x ./gradlew
RUN ./gradlew build
#RUN sed -i -e 's/^android {/android {\n lintOptions {\n    abortOnError false\n  }/' ${testdir}/build.gradle
#RUN chmod og+x ${testdir}/gradlew
#RUN ${testdir}/gradlew build

CMD sleep 3600
