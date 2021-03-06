/*
 * Copyright 2016 SimplifyOps, Inc. (http://simplifyops.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/*
* ResourceModelSource.java
* 
* User: Greg Schueler <a href="mailto:greg@dtosolutions.com">greg@dtosolutions.com</a>
* Created: 7/19/11 10:45 AM
* 
*/
package com.dtolabs.rundeck.core.resources;

import com.dtolabs.rundeck.core.common.INodeSet;

/**
 * ResourceModelSource produces INodeSet
 *
 * @author Greg Schueler <a href="mailto:greg@dtosolutions.com">greg@dtosolutions.com</a>
 */
public interface ResourceModelSource {
    public INodeSet getNodes() throws ResourceModelSourceException;

    /**
     * @return the type of source, which may be writeable, by default return READ_ONLY.  If it is writeable, it must
     * implement
     * {@link WriteableModelSource}
     */
    default SourceType getSourceType() {
        return SourceType.READ_ONLY;
    }

    /**
     * @return a WriteableModelSource if the source type is READ_WRITE
     */
    default WriteableModelSource getWriteable() {
        return null;
    }
}
