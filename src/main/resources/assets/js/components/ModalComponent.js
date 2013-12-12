/** @jsx React.DOM */

define([
  "jquery",
  "React"
], function($, React) {
  return React.createClass({
    componentDidMount: function() {
      var _this = this;

      $(document).on("keyup.modalComponent", function(event) {
        if (event.keyCode === 27) { _this.destroy(); }
      });
    },
    componentWillUnmount: function() {
      $(document).off(".modalComponent");
    },
    destroy: function(event) {
      var domNode = this.getDOMNode();
      if (this.props.onDestroy != null) this.props.onDestroy();

      // TODO(ssorallen): Why does this need to unmount from the parentNode?
      // If it is unmounted from `domNode`, the second render throws an
      // invariant exception.
      React.unmountComponentAtNode(domNode.parentNode);
    },
    onClick: function(event) {
      if (event.target.className === "modal" ||
          event.target.className === "modal-dialog") {
        this.destroy();
      }
    },
    render: function() {
      return (
        <div>
          <div className="modal" onClick={this.onClick}>
            <div className="modal-dialog">
              <div className="modal-content">
                {this.props.children}
              </div>
            </div>
          </div>
          <div className="modal-backdrop fade in"></div>
        </div>
      );
    }
  });
});
