define([""], function () {

    "use strict";

    //var console;

    var Logger = function (name, active) {

        return {
            log: function (msg) {
                return;
                //if (console && console.log) { console.log("[" + name + "] " + msg); }
            },
            info: function (msg) {
                /*console.log(arguments);

                 var callstack = [];

                 try {

                 i.d = s;
                 }catch (e) {

                 var lines = e.stack.split('\n');
                 for (var i=0, len=lines.length; i<len; i+=1) {
                 //if (lines[i].match(/^\s*[A-Za-z0-9\-_\$]+\(/)) {
                 callstack.push(lines[i]);
                 //}
                 }
                 console.log(callstack);

                 console.log(e.name);
                 console.log(e.type);
                 console.log(e.arguments);
                 console.log(e.stack);

                 }*/
                //if (console && console.info) { console.info("[" + name + "] " + msg); }
                return;
            },
            warn: function (msg) {
                //if (console && console.warn) { console.warn("[" + name + "] " + msg); }
                return;
            },
            error: function (msg) {
                //if (console && console.error) { console.error("[" + name + "] " + msg); }
                return;
            },
            debug: function (msg) {
                //if (console && console.debug) { console.debug(msg); }
                return;
            },
            trace: function () {
                return;
                //if (console && console.trace) { console.trace(); }
            }
        };

    };

    return Logger;

});