#!/bin/sh

SCRIPT_DIR="$( cd "$( dirname "$0" )" && pwd )"
CUSTOMER=keonn
NAME="_embedded"
SOURCE_DIR="$SCRIPT_DIR"/../src
RUN_DIR="$SCRIPT_DIR"/..
DEPLOY="$SCRIPT_DIR"
DATE=`date +%Y%m%d_`

echo "SCRIPT_DIR: $SCRIPT_DIR"
cd "$SOURCE_DIR"

# create tmp folder
mkdir "$RUN_DIR"/tmp_sample_dist
cd "$RUN_DIR"/tmp_sample_dist

# copy run scripts
cp "$SCRIPT_DIR"/run*.sh "$RUN_DIR"/tmp_sample_dist

# copy sample libs
mkdir  "$RUN_DIR"/tmp_sample_dist/lib/
cp "$RUN_DIR"/lib/slf4j-api-1.6.1.jar "$RUN_DIR"/tmp_sample_dist/lib/
cp "$RUN_DIR"/lib/JCLAP-1.1.jar "$RUN_DIR"/tmp_sample_dist/lib/
cp "$RUN_DIR"/lib/bsh-core-2.0b4.jar "$RUN_DIR"/tmp_sample_dist/lib/
cp "$RUN_DIR"/lib/keonn-util.jar "$RUN_DIR"/tmp_sample_dist/lib/
cp "$RUN_DIR"/lib/keonn-spec.jar "$RUN_DIR"/tmp_sample_dist/lib/
cp "$RUN_DIR"/lib/keonn-adrd.jar "$RUN_DIR"/tmp_sample_dist/lib/
cp "$RUN_DIR"/lib/keonn-core.jar "$RUN_DIR"/tmp_sample_dist/lib/

# copy class files
mkdir  "$RUN_DIR"/tmp_sample_dist/bin
cp -r "$SOURCE_DIR"/../bin "$RUN_DIR"/tmp_sample_dist

# copy native-libs
mkdir  "$RUN_DIR"/tmp_sample_dist/native-lib/
cp "$RUN_DIR"/native-lib/librxtxSerial.so "$RUN_DIR"/tmp_sample_dist/native-lib/

# copy source files
mkdir  "$RUN_DIR"/tmp_sample_dist/src
cp -r "$SOURCE_DIR" "$RUN_DIR"/tmp_sample_dist

FILENAME="$DATE$CUSTOMER$NAME"
echo "$FILENAME"
echo "file $DATE $RUN_DIR/dist/$DATE$CUSTOMER$NAME.zip"
mkdir "$RUN_DIR"/dist
rm -f "$RUN_DIR"/dist/$DATE$CUSTOMER$NAME.zip
zip -r "$RUN_DIR"/dist/$DATE$CUSTOMER$NAME.zip *
rm -rf "$RUN_DIR"/tmp_sample_dist
