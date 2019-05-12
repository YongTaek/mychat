import React from 'react';

import {connect} from 'react-redux';

import MyMessageContainer from "./MyMessageContainer";
import OtherMessageContainer from "./OtherMessageContainer";
import {bindActionCreators} from "redux";
import {readMessage} from "../actions";

class MessageContainer extends React.Component {
    constructor(props) {
        super(props);
    }

    componentDidMount() {
        console.debug("mounted");
        const {message} = this.props;
        this.props.readMessage(message.messageId);
    }

    render() {
        const {profile} = this.props;
        const {message} = this.props;
        if (message.id === profile.login)
            return <MyMessageContainer body={message}/>;
        else
            return <OtherMessageContainer body={message}/>;
    }
}

export default connect(s => s.oauth, d=> bindActionCreators({readMessage}, d))(MessageContainer);
