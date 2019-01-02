/*-
 * ===============================================================================L
 * Orko UI
 * ================================================================================
 * Copyright (C) 2018 - 2019 Graham Crockford
 * ================================================================================
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 * ===============================================================================E
 */
import React from "react"
import { connect } from "react-redux"
import Immutable from "seamless-immutable"

import StopTakeProfit from "../components/StopTakeProfit"

import * as focusActions from "../store/focus/actions"
import * as jobActions from "../store/job/actions"
import * as jobTypes from "../services/jobTypes"
import { getSelectedCoin } from "../selectors/coins"

import uuidv4 from "uuid/v4"

class StopTakeProfitContainer extends React.Component {
  constructor(props) {
    super(props)
    this.state = {
      job: Immutable({
        lowPrice: "",
        lowLimitPrice: "",
        lowTrailing: false,
        highPrice: "",
        highLimitPrice: "",
        highTrailing: false,
        initialTrailingStop: "",
        amount: "",
        direction: "BUY"
      })
    }
  }

  onChange = job => {
    this.setState({
      job: job
    })
  }

  onFocus = focusedProperty => {
    this.props.dispatch(
      focusActions.setUpdateAction(value => {
        console.log("Set focus to" + focusedProperty)
        this.setState(prev => ({
          job: prev.job.merge({
            [focusedProperty]: value
          })
        }))
      })
    )
  }

  createJob = () => {
    const tickTrigger = {
      exchange: this.props.coin.exchange,
      counter: this.props.coin.counter,
      base: this.props.coin.base
    }

    const limitOrder = limitPrice => ({
      jobType: jobTypes.LIMIT_ORDER,
      id: uuidv4(),
      direction: this.state.job.direction,
      tickTrigger,
      amount: this.state.job.amount,
      limitPrice
    })

    const trailingOrder = (startPrice, stopPrice, limitPrice) => ({
      jobType: jobTypes.SOFT_TRAILING_STOP,
      id: uuidv4(),
      direction: this.state.job.direction,
      tickTrigger,
      amount: this.state.job.amount,
      startPrice,
      lastSyncPrice: startPrice,
      stopPrice,
      limitPrice
    })

    return {
      jobType: jobTypes.OCO,
      id: uuidv4(),
      tickTrigger: tickTrigger,
      low: this.state.job.lowPrice
        ? {
            thresholdAsString: this.state.job.lowPrice,
            job: this.state.job.lowTrailing
              ? trailingOrder(
                  this.state.job.lowPrice,
                  this.state.job.initialTrailingStop,
                  this.state.job.lowLimitPrice
                )
              : limitOrder(this.state.job.lowLimitPrice)
          }
        : null,
      high: this.state.job.highPrice
        ? {
            thresholdAsString: this.state.job.highPrice,
            job: this.state.job.highTrailing
              ? trailingOrder(
                  this.state.job.highPrice,
                  this.state.job.initialTrailingStop,
                  this.state.job.highLimitPrice
                )
              : limitOrder(this.state.job.highLimitPrice)
          }
        : null
    }
  }

  onSubmit = async () => {
    this.props.dispatch(jobActions.submitJob(this.createJob()))
  }

  render() {
    return (
      <StopTakeProfit
        job={this.state.job}
        onChange={this.onChange}
        onFocus={this.onFocus}
        onSubmit={this.onSubmit}
        coin={this.props.coin}
      />
    )
  }
}

function mapStateToProps(state) {
  return {
    auth: state.auth,
    coin: getSelectedCoin(state)
  }
}

export default connect(mapStateToProps)(StopTakeProfitContainer)
