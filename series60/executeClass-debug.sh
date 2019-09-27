#
# Copyright (c) 2016 Keonn Technologies S.L.
# 
# THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
# IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
# FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
# AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
# LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
# OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
# THE SOFTWARE.
#

#!/bin/sh

JAVA=/home/keonn/java/bin/java
$JAVA -Xdebug -Xrunjdwp:transport=dt_socket,server=y,suspend=y,address=8000 -Dcom.keonn.os.io.SampleDelay=1000 -Dcom.keonn.os.io.SamplePeriod=30 -Dcom.keonn.system.defs=adrd-60-series -Dcom.keonn.system.model=0092003 -Dgnu.io.rxtx.SerialPorts=/dev/ttyO1:/dev/ttyO2 -Djava.library.path=native-lib \
-cp lib/mqtt-client-0.4.0.jar:lib/slf4j-api-1.6.1.jar:lib/JCLAP-1.1.jar:lib/bsh-core-2.0b4.jar:lib/RXTXcomm.jar:lib/keonn-adrd.jar:lib/keonn-util.jar:lib/keonn-framework.jar:lib/keonn-spec.jar:lib/keonn-core.jar:lib/AdvanNetLib.jar:bin/ \
$*
