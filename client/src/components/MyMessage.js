import React from 'react';
import ContentContainer from "../containers/ContentContainer";
import UnReadCountContainer from "../containers/UnReadCountContainer";

export default ({body}) => (
    <div style={{
        float: 'right',
        marginRight: '2%',
        padding: '0.5%',
        display: 'grid',
        gridTemplateColumns: 'auto auto'
    }}>
        <UnReadCountContainer body={body}/>
        <div style={{
            marginLeft: '0.2em',
            backgroundColor: 'cornflowerblue',
            display: 'table',
            color: 'white'
        }}>
            <ContentContainer contents={body.contents}/>
        </div>
    </div>
)
