#!/bin/bash -v

git pull --rebase || exit -1

#Delete all untracked files and directories (-d), even ignored ones (-x).
git clean -d -x --force --quiet

rm -rf ~/.m2/repository/me/sneer/

./gradlew clean check install || exit -1

cd android && ./gradlew clean && cd .. || exit -1

#Reset environment sucessful
