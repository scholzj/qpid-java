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
define(["dojo/_base/xhr",
        "dojo/parser",
        "dojo/query",
        "dojo/_base/connect",
        "dijit/registry",
        "qpid/common/properties",
        "qpid/common/updater",
        "qpid/common/util",
        "qpid/common/formatter",
        "qpid/common/UpdatableStore",
        "qpid/management/addBinding",
        "dojox/grid/EnhancedGrid",
        "dojox/html/entities",
        "dojo/text!showExchange.html",
        "dojo/domReady!"],
    function (xhr,
              parser,
              query,
              connect,
              registry,
              properties,
              updater,
              util,
              formatter,
              UpdatableStore,
              addBinding,
              EnhancedGrid,
              entities,
              template)
    {

        function Exchange(name, parent, controller)
        {
            this.name = name;
            this.controller = controller;
            this.management = controller.management;
            this.modelObj = {
                type: "exchange",
                name: name,
                parent: parent
            };
        }

        Exchange.prototype.getExchangeName = function ()
        {
            return this.name;
        };

        Exchange.prototype.getVirtualHostName = function ()
        {
            return this.modelObj.parent.name;
        };

        Exchange.prototype.getVirtualHostNodeName = function ()
        {
            return this.modelObj.parent.parent.name;
        };

        Exchange.prototype.getTitle = function ()
        {
            return "Exchange: " + this.name;
        };

        Exchange.prototype.open = function (contentPane)
        {
            var that = this;
            this.contentPane = contentPane;

            contentPane.containerNode.innerHTML = template;
            parser.parse(contentPane.containerNode)
                .then(function (instances)
                {

                    that.exchangeUpdater = new ExchangeUpdater(that);
                    var addBindingButton = query(".addBindingButton", contentPane.containerNode)[0];
                    connect.connect(registry.byNode(addBindingButton), "onClick", function (evt)
                    {
                        addBinding.show(that.management, that.modelObj);
                    });

                    var deleteBindingButton = query(".deleteBindingButton", contentPane.containerNode)[0];
                    connect.connect(registry.byNode(deleteBindingButton), "onClick", function (evt)
                    {
                        that.deleteBindings();
                    });

                    var isStandard = util.isReservedExchangeName(that.name);
                    var deleteExchangeButton = query(".deleteExchangeButton", contentPane.containerNode)[0];
                    var node = registry.byNode(deleteExchangeButton);
                    if (isStandard)
                    {
                        node.set('disabled', true);
                    }
                    else
                    {
                        connect.connect(node, "onClick", function (evt)
                        {
                            that.deleteExchange();
                        });
                    }

                    that.exchangeUpdater.update(function ()
                    {
                        updater.add(that.exchangeUpdater)
                    });
                });
        };

        Exchange.prototype.close = function ()
        {
            updater.remove(this.exchangeUpdater);
        };

        Exchange.prototype.deleteBindings = function ()
        {
            util.deleteSelectedObjects(this.exchangeUpdater.bindingsGrid.grid,
                "Are you sure you want to delete binding",
                this.management,
                {
                    type: "binding",
                    parent: this.modelObj
                },
                this.exchangeUpdater);
        }

        function ExchangeUpdater(exchangeTab)
        {
            var that = this;
            this.tabObject = exchangeTab;
            this.management = exchangeTab.controller.management;
            this.modelObj = exchangeTab.modelObj;
            var containerNode = exchangeTab.contentPane.containerNode;

            function findNode(name)
            {
                return query("." + name, containerNode)[0];
            }

            function storeNodes(names)
            {
                for (var i = 0; i < names.length; i++)
                {
                    that[names[i]] = findNode(names[i]);
                }
            }

            storeNodes(["name",
                        "type",
                        "state",
                        "durable",
                        "lifetimePolicy",
                        "alertRepeatGap",
                        "alertRepeatGapUnits",
                        "alertThresholdMessageAge",
                        "alertThresholdMessageAgeUnits",
                        "alertThresholdMessageSize",
                        "alertThresholdMessageSizeUnits",
                        "alertThresholdQueueDepthBytes",
                        "alertThresholdQueueDepthBytesUnits",
                        "alertThresholdQueueDepthMessages",
                        "msgInRate",
                        "bytesInRate",
                        "bytesInRateUnits",
                        "msgDropRate",
                        "bytesDropRate",
                        "bytesDropRateUnits"]);

            that.exchangeData = {};

            that.bindingsGrid = new UpdatableStore([], findNode("bindings"), [{
                name: "Queue",
                field: "queue",
                width: "40%"
            }, {
                name: "Binding Key",
                field: "name",
                width: "30%"
            }, {
                name: "Arguments",
                field: "argumentString",
                width: "30%"
            }], null, {
                keepSelection: true,
                plugins: {
                    pagination: {
                        pageSizes: ["10", "25", "50", "100"],
                        description: true,
                        sizeSwitch: true,
                        pageStepper: true,
                        gotoButton: true,
                        maxPageStep: 4,
                        position: "bottom"
                    },
                    indirectSelection: true

                }
            }, EnhancedGrid);
        }

        ExchangeUpdater.prototype.updateHeader = function ()
        {
            this.name.innerHTML = entities.encode(String(this.exchangeData["name"]));
            this["type"].innerHTML = entities.encode(String(this.exchangeData["type"]));

            this.state.innerHTML = entities.encode(String(this.exchangeData["state"]));
            this.durable.innerHTML = entities.encode(String(this.exchangeData["durable"]));
            this.lifetimePolicy.innerHTML = entities.encode(String(this.exchangeData["lifetimePolicy"]));

        };

        ExchangeUpdater.prototype.update = function (callback)
        {

            var thisObj = this;

            this.management.load(this.modelObj, {excludeInheritedContext: true})
                .then(function (data)
                {
                    thisObj.exchangeData = data[0];

                    if (callback)
                    {
                        callback();
                    }

                    util.flattenStatistics(thisObj.exchangeData);

                    var bindings = thisObj.exchangeData["bindings"];

                    if (bindings)
                    {
                        for (var i = 0; i < bindings.length; i++)
                        {
                            if (bindings[i].arguments)
                            {
                                bindings[i].argumentString = dojo.toJson(bindings[i].arguments);
                            }
                            else
                            {
                                bindings[i].argumentString = "";
                            }
                        }
                    }

                    var sampleTime = new Date();

                    thisObj.updateHeader();

                    var messageIn = thisObj.exchangeData["messagesIn"];
                    var bytesIn = thisObj.exchangeData["bytesIn"];
                    var messageDrop = thisObj.exchangeData["messagesDropped"];
                    var bytesDrop = thisObj.exchangeData["bytesDropped"];

                    if (thisObj.sampleTime)
                    {
                        var samplePeriod = sampleTime.getTime() - thisObj.sampleTime.getTime();

                        var msgInRate = (1000 * (messageIn - thisObj.messageIn)) / samplePeriod;
                        var msgDropRate = (1000 * (messageDrop - thisObj.messageDrop)) / samplePeriod;
                        var bytesInRate = (1000 * (bytesIn - thisObj.bytesIn)) / samplePeriod;
                        var bytesDropRate = (1000 * (bytesDrop - thisObj.bytesDrop)) / samplePeriod;

                        thisObj.msgInRate.innerHTML = msgInRate.toFixed(0);
                        var bytesInFormat = formatter.formatBytes(bytesInRate);
                        thisObj.bytesInRate.innerHTML = "(" + bytesInFormat.value;
                        thisObj.bytesInRateUnits.innerHTML = bytesInFormat.units + "/s)";

                        thisObj.msgDropRate.innerHTML = msgDropRate.toFixed(0);
                        var bytesDropFormat = formatter.formatBytes(bytesDropRate);
                        thisObj.bytesDropRate.innerHTML = "(" + bytesDropFormat.value;
                        thisObj.bytesDropRateUnits.innerHTML = bytesDropFormat.units + "/s)"

                    }

                    thisObj.sampleTime = sampleTime;
                    thisObj.messageIn = messageIn;
                    thisObj.bytesIn = bytesIn;
                    thisObj.messageDrop = messageDrop;
                    thisObj.bytesDrop = bytesDrop;

                    // update bindings
                    thisObj.bindingsGrid.update(thisObj.exchangeData.bindings)

                }, function (error)
                {
                    util.tabErrorHandler(error, {
                        updater: thisObj,
                        contentPane: thisObj.tabObject.contentPane,
                        tabContainer: thisObj.tabObject.controller.tabContainer,
                        name: thisObj.modelObj.name,
                        category: "Exchange"
                    });
                });
        };

        Exchange.prototype.deleteExchange = function ()
        {
            if (confirm("Are you sure you want to delete exchange '" + this.name + "'?"))
            {
                var that = this;
                this.management.remove(this.modelObj)
                    .then(function (data)
                    {
                        that.contentPane.onClose()
                        that.controller.tabContainer.removeChild(that.contentPane);
                        that.contentPane.destroyRecursive();
                    }, util.xhrErrorHandler);
            }
        }

        return Exchange;
    });
