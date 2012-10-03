require(["w3", "model/job", "model/jobs"], function (W3, Job, Jobs) {

    // for debugging
    window.W3 = W3;
    window.Job = Job;
    window.Jobs = Jobs;

    $(function () {

        var root, template, jobs, socket, VS;

        root = window;

        try {
            template = _.template(document.getElementById("job-template").text);
        } catch(ex) {
            template = _.template("");
        }

        jobs = root.jobs = new Jobs.View({
            el: document.getElementById("jobs"),
            url: "/suite/jobs",
            jobTemplate: template
        });
        jobs.collection.fetch();

        socket = root.socket = new W3.Socket(document.getElementById("jobs").getAttribute("data-url"));
        socket.on("jobupdate", function (data) {
            var job = jobs.collection.get(data.id);
            if (!_.isUndefined(job)) {
                job.set(data);
            } else {
                console.log("unknown job with id: " + data.id);
                console.log(data);
            }
        });

        // for comet, deprecated
        VS = root.VS = {};
        VS.jobupdate = function (job) {
            socket.trigger("jobupdate", job);
        };

        //VS.dashboard.collection.fetch({data: {sort: 'warnings'}});

    });

});