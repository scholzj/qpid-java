/*
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *
 */
define(["dojo/_base/xhr", "dojo/parser", "dojo/string", "dojox/html/entities", "dojo/query", "dojo/domReady!"],
    function (xhr, parser, json, entities, query)
    {

        function FileSystemPreferences(containerNode)
        {
            var that = this;
            xhr.get({
                url: "preferencesprovider/filesystempreferences/show.html",
                sync: true,
                load: function (template)
                {
                    containerNode.innerHTML = template;
                    parser.parse(containerNode)
                        .then(function (instances)
                        {
                            that.preferencesProviderPath =
                                query(".fileSystemPreferencesProviderPath", containerNode)[0];
                        });
                }
            });
        }

        FileSystemPreferences.prototype.update = function (data)
        {
            this.preferencesProviderPath.innerHTML = entities.encode(String(data["path"]));
        };

        return FileSystemPreferences;
    });
