define([
  "Backbone",
  "models/Task"
], function(Backbone, Task) {
  return Backbone.Collection.extend({
    initialize: function(models, options) {
      this.options = options;
    },
    model: Task,
    parse: function(data) {
      return data[this.options.appId];
    },
    url: function() {
      return "v1/apps/" + this.options.appId + "/tasks";
    }
  });
});
