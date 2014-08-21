#Delete all untracked files and directories (-d), even ignored ones (-x).
git clean -x -d --force --quiet

#Revert all modified files.
git reset --hard

git pull --rebase

./gradlew install

./gradlew eclipse

cd android && ./gradlew build eclipse && cd -

if [ -d "../rockpaperscissors" ]; then
  cp -f android/android-api/bin/android-api.jar ../rockpaperscissors/libs/
fi
