import React from 'react';
import {connect} from "react-redux";
import {readMessage} from "../actions";
import {bindActionCreators} from "redux";

class UnReadCountContainer extends React.Component {


    render() {
        const {body} = this.props;
        const unReadUsers = body.unReadUsers;
        const {profile} = this.props;
        _.map(Object.keys(unReadUsers), id => {
           if (id === profile.login && !unReadUsers[id]) {
               this.props.readMessage();
           }
        });
        const count = _.filter(unReadUsers, b => !b);
        return (
            <div style={{
                color: 'yellowGreen'
            }}>
                {count.length}
            </div>
        );
    }
}

export default connect(s => s.oauth, d => bindActionCreators({readMessage}, d))(UnReadCountContainer);
