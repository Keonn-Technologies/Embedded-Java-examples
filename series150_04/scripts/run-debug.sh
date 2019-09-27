#!/bin/sh

JAVA=/opt/java/bin/java
$JAVA -Xdebug -Xrunjdwp:transport=dt_socket,server=y,suspend=y,address=8000 -Dcom.keonn.os.io.SampleDelay=1000 -Dcom.keonn.os.io.SamplePeriod=30 -Dcom.keonn.system.defs=adrd-150.04-series -Dcom.keonn.system.model=0091104 -Dgnu.io.rxtx.SerialPorts=/dev/ttyO1:/dev/ttyO2 -Djava.library.path=native-lib \
-cp lib/slf4j-api-1.6.1.jar:lib/JCLAP-1.1.jar:lib/bsh-core-2.0b4.jar:lib/RXTXcomm.jar:lib/keonn-adrd.jar:lib/keonn-util.jar:lib/keonn-framework.jar:lib/keonn-spec.jar:lib/keonn-core.jar:lib/AdvanNetLib.jar:bin/ \
com.keonn.embedded.EmbeddedUtil $* 
