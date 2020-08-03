
# OSPREY GUI

*A cross-platform GUI for OSPREY 3.*

Builds for the GUI can be downloaded from our automatic build server [here](https://dev.azure.com/donaldlab/osprey/_build?definitionId=1&_a=summary).

**OSPREY is developed and maintained by the Donald Lab in the Department of Computer Science at Duke University**

- [Donald Lab](http://www.cs.duke.edu/donaldlab/home.php)
- [Department of Computer Science](http://www.cs.duke.edu)
- [Duke University](https://www.duke.edu/)

For an introduction to OSPREY 3 and its new features please read **Journal of Computational Chemistry 2018; 39(30): 2494-2507**

- [Journal of Computational Chemistry](https://onlinelibrary.wiley.com/doi/10.1002/jcc.25522)
- [Cover Image (Osprey)](http://www.cs.duke.edu/brd/papers/jcc18-osprey3point0/cover-jcc.25043.pdf)
- [PDF of paper](http://www.cs.duke.edu/brd/papers/jcc18-osprey3point0/jcc18-osprey-donald.pdf)

Citation requirements
===

We require everyone who publishes or presents results from OSPREY to please mention the name "OSPREY," and to cite our papers as described [here](https://raw.githubusercontent.com/donaldlab/OSPREY3/master/CITING_OSPREY.txt) (especially our new paper introducing OSPREY 3). 


License
===

[GPLv2](https://www.gnu.org/licenses/gpl-2.0.html)

Copyright (C) 2020 Duke University

This program is free software; you can redistribute it and/or
modify it under the terms of the [GNU General Public License version 2](https://www.gnu.org/licenses/gpl-2.0.html)
as published by the Free Software Foundation.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

The full text of the GPLv2 is included in the accompanying [LICENSE.txt](./LICENSE.txt)

## Building

Download OpenJDK 14
https://jdk.java.net/14/

Set `systemProp.jpackage.home` in `gradle.properties` with path to JDK 14

### For Windows:

Install WiX tools:
https://wixtoolset.org/
