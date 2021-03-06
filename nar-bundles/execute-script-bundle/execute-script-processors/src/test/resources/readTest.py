# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
import os, re, subprocess

class SimpleJythonReader(ReaderScript):
    def route(self, input):
        logger.info("In route")
        returnid = os.system("c:\\cygwin\\bin\\echo GOOD")
        fname = self.getAttribute("filename")
        counter = self.getAttribute("counter")
        fname = fname + '.' + counter
        self.setAttribute("filename", fname)
        for line in FileUtil.wrap(input):
            if re.match("^sed",line): return self.FAIL_RELATIONSHIP

        return self.SUCCESS_RELATIONSHIP



instance = SimpleJythonReader()
